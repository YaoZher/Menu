package com.example.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class ConcurrentReviewTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void simultaneousApprovalOfOneSubmissionPublishesExactlyOneDish() throws Exception {
        String dishName = "同时审核的菜品";
        MvcResult submissionResult = mvc.perform(multipart("/api/submissions")
                        .param("operation", "CREATE").param("name", dishName).with(csrf()))
                .andExpect(status().isAccepted()).andReturn();
        long submissionId = json(submissionResult).path("id").asLong();
        assertThat(json(mvc.perform(get("/api/menu")).andExpect(status().isOk()).andReturn()).size()).isZero();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> approveWhenReleased(submissionId, "reviewer-a", ready, start));
            Future<MvcResult> second = executor.submit(() -> approveWhenReleased(submissionId, "reviewer-b", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("both independent request threads reached the barrier").isTrue();
            start.countDown();

            List<MvcResult> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult approved = results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
            MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(json(approved).path("status").asText()).isEqualTo("APPROVED");
            assertThat(json(conflict).path("message").asText()).isNotBlank();

            JsonNode menu = json(mvc.perform(get("/api/menu")).andExpect(status().isOk()).andReturn());
            assertThat(menu.size()).as("one approved submission must create only one public dish").isEqualTo(1);
            assertThat(menu.get(0).path("name").asText()).isEqualTo(dishName);
            assertThat(menu.get(0).path("id").asLong()).isEqualTo(json(approved).path("dishId").asLong());

            mvc.perform(get("/api/chef/submissions").param("status", "ALL")
                            .with(user("auditor").roles("CHEF")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("APPROVED"));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("request workers terminated").isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-uuid", "1-1-1-1-1", "00000000000000000000000000000000",
        "00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff",
        "javascript:alert(1)", "'OR'1'='1"
    })
    void invalidOrUnknownMediaIdentifiersReturnNotFoundForPublicAndChef(String id) throws Exception {
        mvc.perform(get("/media/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/media/{id}", id).with(user("auditor").roles("CHEF")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    private MvcResult approveWhenReleased(long submissionId, String reviewer,
                                         CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent review start barrier timed out");
        return mvc.perform(post("/api/chef/submissions/{id}/approve", submissionId)
                        .with(user(reviewer).roles("CHEF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"并发审核\"}"))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
