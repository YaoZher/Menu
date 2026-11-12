package com.example.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:guestshopping;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.username=head-chef",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class GuestShoppingTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void visitorSelectionAppearsInConfiguredChefsListAndPublicList() throws Exception {
        long dishId = published();
        JsonNode entry = add(dishId, false);
        assertThat(entry.path("dish").path("id").asLong()).isEqualTo(dishId);
        assertThat(entry.path("ingredients").get(0).path("text").asText()).isEqualTo("鸡蛋 2 个");
        JsonNode stored = findEntry("/api/shopping", dishId, false);
        assertThat(stored.path("id")).isEqualTo(entry.path("id"));
        assertThat(stored.path("dish")).isEqualTo(entry.path("dish"));
        assertThat(stored.path("ingredients")).isEqualTo(entry.path("ingredients"));
        assertThat(findEntry("/api/chef/shopping", dishId, true)).isEqualTo(stored);
        JsonNode anotherOwner = json(mvc.perform(get("/api/chef/shopping").with(user("chef").roles("CHEF")))
                .andExpect(status().isOk()).andReturn());
        assertThat(anotherOwner).isEmpty();
    }

    @Test void realAnonymousCsrfTokenWorksAndMissingTokenDoesNotWrite() throws Exception {
        long dishId = published();
        mvc.perform(addRequest(dishId, false)).andExpect(status().isForbidden());
        assertThat(findEntry("/api/shopping", dishId, false)).isNull();
        MvcResult tokenResult = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = json(tokenResult);
        MockHttpSession session = (MockHttpSession) tokenResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(addRequest(dishId, false).session(session)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dish.id").value(dishId));
    }

    @Test void visitorsCannotChangeChecksRemoveDishesOrBypassChefActions() throws Exception {
        JsonNode entry = add(published(), false);
        String id = entry.path("id").asText();
        String ingredientId = entry.path("ingredients").get(0).path("id").asText();
        for (String prefix : List.of("/api/shopping", "/api/chef/shopping")) {
            mvc.perform(patch(prefix + "/{id}/ingredients/{ingredient}", id, ingredientId).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete(prefix + "/{id}", id).with(csrf())).andExpect(status().isUnauthorized());
        }
        mvc.perform(multipart("/api/chef/dishes").param("operation", "DELETE")
                        .param("dishId", entry.path("dish").path("id").asText())
                        .param("expectedVersion", entry.path("dish").path("version").asText()).with(csrf()))
                .andExpect(status().isUnauthorized());
        JsonNode current = findEntry("/api/shopping", entry.path("dish").path("id").asLong(), false);
        assertThat(current.path("id")).isEqualTo(entry.path("id"));
        assertThat(current.path("ingredients").get(0).path("checked").asBoolean()).isFalse();
    }

    @Test void visitorAndChefSelectionsDeduplicateWithoutResettingChecks() throws Exception {
        long dishId = published();
        JsonNode entry = add(dishId, true);
        mvc.perform(patch("/api/chef/shopping/{id}/ingredients/{ingredient}", entry.path("id").asText(),
                        entry.path("ingredients").get(0).path("id").asText())
                        .with(user("head-chef").roles("CHEF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(status().isOk());
        JsonNode duplicate = add(dishId, false);
        assertThat(duplicate.path("id")).isEqualTo(entry.path("id"));
        assertThat(duplicate.path("ingredients").get(0).path("checked").asBoolean()).isTrue();
        assertThat(add(dishId, true)).isEqualTo(duplicate);
    }

    @Test void concurrentVisitorAndChefSelectionsShareOneEntry() throws Exception {
        long dishId = published();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(false, true).stream().map(chef -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                return add(dishId, chef);
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JsonNode first = tasks.get(0).get(15, TimeUnit.SECONDS);
            JsonNode second = tasks.get(1).get(15, TimeUnit.SECONDS);
            assertThat(first.path("id")).isEqualTo(second.path("id"));
            JsonNode list = json(mvc.perform(get("/api/shopping")).andExpect(status().isOk()).andReturn());
            long count = java.util.stream.StreamSupport.stream(list.spliterator(), false)
                    .filter(entry -> entry.path("dish").path("id").asLong() == dishId).count();
            assertThat(count).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void invalidAndDeletedDishIdsAreRejected() throws Exception {
        for (String body : List.of("{}", "{\"dishId\":0}", "{\"dishId\":-1}")) {
            mvc.perform(post("/api/shopping").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(addRequest(Long.MAX_VALUE, false).with(csrf())).andExpect(status().isNotFound());
        long dishId = published();
        JsonNode entry = add(dishId, false);
        mvc.perform(multipart("/api/chef/dishes").param("operation", "DELETE")
                        .param("dishId", Long.toString(dishId))
                        .param("expectedVersion", entry.path("dish").path("version").asText())
                        .with(user("head-chef").roles("CHEF")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(findEntry("/api/shopping", dishId, false)).isNull();
        mvc.perform(addRequest(dishId, false).with(csrf())).andExpect(status().isNotFound());
    }

    private long published() throws Exception {
        JsonNode receipt = json(mvc.perform(multipart("/api/chef/dishes").param("operation", "CREATE")
                        .param("name", "访客点餐-" + UUID.randomUUID()).param("notes", "• 鸡蛋 2 个\n// 打散")
                        .with(user("head-chef").roles("CHEF")).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        return receipt.path("dishId").asLong();
    }

    private MockHttpServletRequestBuilder addRequest(long dishId, boolean chef) {
        var request = post(chef ? "/api/chef/shopping" : "/api/shopping")
                .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":" + dishId + "}");
        return chef ? request.with(user("head-chef").roles("CHEF")) : request;
    }

    private JsonNode add(long dishId, boolean chef) throws Exception {
        return json(mvc.perform(addRequest(dishId, chef).with(csrf())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode findEntry(String path, long dishId, boolean chef) throws Exception {
        var request = get(path);
        if (chef) request.with(user("head-chef").roles("CHEF"));
        for (JsonNode entry : json(mvc.perform(request).andExpect(status().isOk()).andReturn())) {
            if (entry.path("dish").path("id").asLong() == dishId) return entry;
        }
        return null;
    }

    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
}
