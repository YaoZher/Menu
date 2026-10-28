package com.example.menu;

import com.example.menu.domain.ShoppingEntry;
import com.example.menu.domain.ShoppingEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:chefshopping;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class ChefShoppingTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ShoppingEntryRepository entries;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void sessionDiscoveryAndChefPagesEnforceAuthentication() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.username").doesNotExist());
        mvc.perform(get("/api/session").with(user("chef").roles("CHEF"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.username").value("chef"));
        mvc.perform(get("/api/session").with(user("visitor").roles("USER")))
                .andExpect(jsonPath("$.authenticated").value(false));
        for (String path : List.of("/chef", "/chef/", "/chef.html", "/shopping", "/shopping/")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/chef/login"));
            mvc.perform(get(path).with(user("chef").roles("CHEF")))
                    .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
        mvc.perform(get("/api/chef/shopping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chef/shopping").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/chef/dishes").param("operation", "CREATE").param("name", "未登录")
                .with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chef/shopping").contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":1}")
                .with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test void directChefChangesAreAtomicAndAlreadyApprovedWhileVisitorChangesWait() throws Exception {
        String name = name();
        JsonNode created = save(create(name, "• 鸡蛋 2 个\n// 打散"), "chef");
        assertThat(created.path("status").asText()).isEqualTo("APPROVED");
        assertThat(created.path("reviewedBy").asText()).isEqualTo("chef");
        JsonNode dish = findDish(created.path("dishId").asLong());
        assertThat(dish.path("noteLines").get(0).path("type").asText()).isEqualTo("INGREDIENT");
        JsonNode changed = save(change(dish, "UPDATE").param("name", name).param("notes", "• 鸡蛋 3 个\n// 打散"), "chef");
        assertThat(changed.path("status").asText()).isEqualTo("APPROVED");
        JsonNode updated = findDish(dish.path("id").asLong());
        assertThat(updated.path("notes").asText()).contains("3 个");
        assertThat(updated.path("version").asLong()).isGreaterThan(dish.path("version").asLong());
        mvc.perform(change(dish, "UPDATE").param("name", name).param("notes", "旧版本")
                        .with(user("chef").roles("CHEF")).with(csrf())).andExpect(status().isConflict());
        JsonNode visitor = json(mvc.perform(multipart("/api/submissions").param("operation", "CREATE")
                .param("name", name + "访客").with(csrf())).andExpect(status().isAccepted()).andReturn());
        assertThat(visitor.path("status").asText()).isEqualTo("PENDING");
        for (JsonNode pending : list("/api/chef/submissions?status=PENDING", "chef")) {
            assertThat(pending.path("after").path("name").asText()).isNotEqualTo(name);
        }
        save(change(updated, "DELETE"), "chef");
        assertThat(findDish(updated.path("id").asLong())).isNull();
    }

    @Test void allChefWritesRequireCsrfAndRejectInvalidInputs() throws Exception {
        JsonNode dish = published("• 米 1 碗");
        JsonNode entry = add(dish, "csrf-chef");
        mvc.perform(create(name(), "").with(user("chef").roles("CHEF"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/chef/shopping").with(user("chef").roles("CHEF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(checkRequest(entry, 0, true).with(user("csrf-chef").roles("CHEF"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/chef/shopping/{id}", entry.path("id").asText()).with(user("csrf-chef").roles("CHEF")))
                .andExpect(status().isForbidden());
        for (String body : List.of("{}", "{\"dishId\":0}", "{\"dishId\":-1}")) {
            mvc.perform(post("/api/chef/shopping").with(user("chef").roles("CHEF")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/chef/shopping/{id}/ingredients/{ingredient}", entry.path("id").asText(),
                        entry.path("ingredients").get(0).path("id").asText())
                .with(user("csrf-chef").roles("CHEF")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void selectionAndChecksPersistAndRepeatedAddsDoNotResetProgress() throws Exception {
        JsonNode dish = published("• 番茄 2 个\n// 切块\n少盐\n· 鸡蛋 3 个");
        JsonNode entry = add(dish, "persist-chef");
        assertThat(entry.path("ingredients").size()).isEqualTo(2);
        assertThat(entry.path("ingredients").get(0).path("text").asText()).isEqualTo("番茄 2 个");
        check(entry, 0, true, "persist-chef");
        JsonNode duplicate = add(dish, "persist-chef");
        assertThat(duplicate.path("id")).isEqualTo(entry.path("id"));
        assertThat(duplicate.path("ingredients").get(0).path("checked").asBoolean()).isTrue();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.flush(); entityManager.clear();
            ShoppingEntry stored = entries.findById(UUID.fromString(entry.path("id").asText())).orElseThrow();
            assertThat(stored.getIngredients()).hasSize(2);
            assertThat(stored.getIngredients().get(0).isChecked()).isTrue();
            assertThat(stored.getIngredients().get(0).getId().toString()).isEqualTo(entry.path("ingredients").get(0).path("id").asText());
        });
        JsonNode unchecked = check(entry, 0, false, "persist-chef");
        assertThat(unchecked.path("ingredients").get(0).path("checked").asBoolean()).isFalse();
        mvc.perform(delete("/api/chef/shopping/{id}", entry.path("id").asText()).with(user("persist-chef").roles("CHEF"))
                .with(csrf())).andExpect(status().isNoContent());
        assertThat(list("/api/chef/shopping", "persist-chef")).isEmpty();
        JsonNode again = add(dish, "persist-chef");
        assertThat(again.path("id")).isNotEqualTo(entry.path("id"));
        assertThat(again.path("ingredients").get(0).path("checked").asBoolean()).isFalse();
    }

    @Test void recipeEditsPreserveUnchangedOccurrencesAndResetChangedIngredients() throws Exception {
        JsonNode dish = published("• 盐\n• 水\n• 盐\n• 番茄 2 个\n// 切块");
        JsonNode entry = add(dish, "sync-chef");
        check(entry, 0, true, "sync-chef");
        check(entry, 2, true, "sync-chef");
        check(entry, 3, true, "sync-chef");
        save(change(dish, "UPDATE").param("name", dish.path("name").asText())
                .param("notes", "• 水\n• 盐\n• 盐\n• 番茄 3 个\n• 葱\n// 先切葱"), "chef");
        JsonNode refreshed = list("/api/chef/shopping", "sync-chef").get(0);
        assertThat(refreshed.path("ingredients").size()).isEqualTo(5);
        assertThat(refreshed.path("ingredients").get(0).path("id")).isEqualTo(entry.path("ingredients").get(1).path("id"));
        for (int index : List.of(1, 2)) assertThat(refreshed.path("ingredients").get(index).path("checked").asBoolean()).isTrue();
        assertThat(refreshed.path("ingredients").get(1).path("id")).isEqualTo(entry.path("ingredients").get(0).path("id"));
        assertThat(refreshed.path("ingredients").get(2).path("id")).isEqualTo(entry.path("ingredients").get(2).path("id"));
        assertThat(refreshed.path("ingredients").get(3).path("checked").asBoolean()).isFalse();
        assertThat(refreshed.path("ingredients").get(3).path("id")).isNotEqualTo(entry.path("ingredients").get(3).path("id"));
        assertThat(refreshed.path("ingredients").get(4).path("checked").asBoolean()).isFalse();
        mvc.perform(checkRequest(entry, 3, false).with(user("sync-chef").roles("CHEF")).with(csrf()))
                .andExpect(status().isConflict());
        JsonNode current = refreshed.path("dish");
        save(change(current, "UPDATE").param("name", current.path("name").asText()).param("notes", "// 只有步骤"), "chef");
        assertThat(list("/api/chef/shopping", "sync-chef").get(0).path("ingredients")).isEmpty();
    }

    @Test void approvedVisitorChangesAlsoSynchronizeAndDeletionCleansEveryOwnersList() throws Exception {
        JsonNode dish = published("• 豆腐\n// 煎");
        add(dish, "owner-one");
        add(dish, "owner-two");
        JsonNode pending = json(mvc.perform(multipart("/api/submissions").param("operation", "UPDATE")
                .param("dishId", dish.path("id").asText()).param("expectedVersion", dish.path("version").asText())
                .param("name", dish.path("name").asText()).param("notes", "• 豆腐\n• 葱\n// 煎").with(csrf()))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(list("/api/chef/shopping", "owner-one").get(0).path("ingredients").size()).isEqualTo(1);
        mvc.perform(post("/api/chef/submissions/{id}/approve", pending.path("id").asLong())
                .with(user("chef").roles("CHEF")).with(csrf())).andExpect(status().isOk());
        for (String owner : List.of("owner-one", "owner-two")) {
            assertThat(list("/api/chef/shopping", owner).get(0).path("ingredients").size()).isEqualTo(2);
        }
        save(change(findDish(dish.path("id").asLong()), "DELETE"), "chef");
        assertThat(list("/api/chef/shopping", "owner-one")).isEmpty();
        assertThat(list("/api/chef/shopping", "owner-two")).isEmpty();
        mvc.perform(post("/api/chef/shopping").with(user("owner-one").roles("CHEF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":" + dish.path("id").asLong() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test void eachChefsChecksArePrivateAndCannotBeChangedByAnotherChef() throws Exception {
        JsonNode dish = published("• 白菜");
        JsonNode first = add(dish, "private-a");
        JsonNode second = add(dish, "private-b");
        check(first, 0, true, "private-a");
        assertThat(list("/api/chef/shopping", "private-b").get(0).path("ingredients").get(0).path("checked").asBoolean()).isFalse();
        mvc.perform(checkRequest(first, 0, false).with(user("private-b").roles("CHEF")).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/chef/shopping/{id}", first.path("id").asText()).with(user("private-b").roles("CHEF"))
                .with(csrf())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/chef/shopping/{id}/ingredients/{ingredient}", second.path("id").asText(),
                        first.path("ingredients").get(0).path("id").asText()).with(user("private-b").roles("CHEF"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(status().isConflict());
    }

    @Test void simultaneousAddsAndIndependentChecksDoNotDuplicateOrLoseProgress() throws Exception {
        JsonNode dish = published("• 土豆\n• 胡萝卜");
        var additions = concurrently(
                () -> add(dish, "parallel-chef"), () -> add(dish, "parallel-chef"));
        assertThat(additions.get(0).path("id")).isEqualTo(additions.get(1).path("id"));
        JsonNode entry = additions.get(0);
        concurrently(() -> check(entry, 0, true, "parallel-chef"), () -> check(entry, 1, true, "parallel-chef"));
        JsonNode stored = list("/api/chef/shopping", "parallel-chef").get(0);
        assertThat(stored.path("ingredients").get(0).path("checked").asBoolean()).isTrue();
        assertThat(stored.path("ingredients").get(1).path("checked").asBoolean()).isTrue();
        assertThat(list("/api/chef/shopping", "parallel-chef").size()).isEqualTo(1);
    }

    private List<JsonNode> concurrently(java.util.concurrent.Callable<JsonNode> first,
                                       java.util.concurrent.Callable<JsonNode> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                return action.call();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String name() { return "采购菜品-" + UUID.randomUUID(); }
    private MockMultipartHttpServletRequestBuilder create(String name, String notes) {
        var request = multipart("/api/chef/dishes");
        request.param("operation", "CREATE").param("name", name).param("notes", notes);
        return request;
    }
    private MockMultipartHttpServletRequestBuilder change(JsonNode dish, String operation) {
        var request = multipart("/api/chef/dishes");
        request.param("operation", operation).param("dishId", dish.path("id").asText())
                .param("expectedVersion", dish.path("version").asText());
        return request;
    }
    private JsonNode save(MockHttpServletRequestBuilder request, String owner) throws Exception {
        return json(mvc.perform(request.with(user(owner).roles("CHEF")).with(csrf())).andExpect(status().isOk()).andReturn());
    }
    private JsonNode published(String notes) throws Exception {
        JsonNode saved = save(create(name(), notes), "chef");
        return findDish(saved.path("dishId").asLong());
    }
    private JsonNode findDish(long id) throws Exception {
        for (JsonNode dish : json(mvc.perform(get("/api/menu")).andExpect(status().isOk()).andReturn())) {
            if (dish.path("id").asLong() == id) return dish;
        }
        return null;
    }
    private JsonNode add(JsonNode dish, String owner) throws Exception {
        return json(mvc.perform(post("/api/chef/shopping").with(user(owner).roles("CHEF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":" + dish.path("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
    }
    private MockHttpServletRequestBuilder checkRequest(JsonNode entry, int index, boolean checked) {
        return patch("/api/chef/shopping/{id}/ingredients/{ingredient}", entry.path("id").asText(),
                entry.path("ingredients").get(index).path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":" + checked + "}");
    }
    private JsonNode check(JsonNode entry, int index, boolean checked, String owner) throws Exception {
        return json(mvc.perform(checkRequest(entry, index, checked).with(user(owner).roles("CHEF")).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }
    private JsonNode list(String path, String owner) throws Exception {
        return json(mvc.perform(get(path).with(user(owner).roles("CHEF"))).andExpect(status().isOk()).andReturn());
    }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
}
