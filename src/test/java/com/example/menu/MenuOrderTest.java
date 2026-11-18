package com.example.menu;

import com.example.menu.domain.DishRepository;
import com.example.menu.domain.ShoppingEntryRepository;
import com.example.menu.domain.SubmissionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:menuorder;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class MenuOrderTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ShoppingEntryRepository entries;
    @Autowired SubmissionRepository submissions;
    @Autowired DishRepository dishes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void resetMenu() {
        entries.deleteAll();
        submissions.deleteAll();
        dishes.deleteAll();
        jdbc.update("update menu_orders set ordered_ids = null, revision = 0, lock_version = 0 where id = 1");
    }

    @Test void existingMenusKeepTheirOriginalOrderUntilFirstSave() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        long third = create("汤");
        assertThat(menuIds()).containsExactly(third, second, first);
        JsonNode initial = order();
        assertThat(ids(initial.path("dishIds"))).containsExactly(third, second, first);
        assertThat(initial.path("revision").asLong()).isZero();
        assertThat(jdbc.queryForObject("select ordered_ids from menu_orders where id = 1", String.class)).isNull();
    }

    @Test void savedOrderPersistsWithoutChangingDishSnapshotsOrInvalidatingPendingApproval() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        JsonNode beforeFirst = dish(first);
        JsonNode beforeSecond = dish(second);
        JsonNode pending = json(mvc.perform(multipart("/api/submissions").with(csrf())
                .param("operation", "UPDATE").param("dishId", Long.toString(first))
                .param("expectedVersion", beforeFirst.path("version").asText())
                .param("name", "米饭").param("notes", "• 大米 1 碗\n// 蒸熟"))
                .andExpect(status().isAccepted()).andReturn());
        JsonNode saved = save(List.of(first, second), order());
        assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(menuIds()).containsExactly(first, second);
        assertThat(order()).isEqualTo(saved);
        assertThat(dish(first)).isEqualTo(beforeFirst);
        assertThat(dish(second)).isEqualTo(beforeSecond);
        assertThat(jdbc.queryForObject("select revision from menu_orders where id = 1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ordered_ids from menu_orders where id = 1", String.class)).isNotNull();

        mvc.perform(post("/api/chef/submissions/{id}/approve", pending.path("id").asLong())
                        .with(user("chef").roles("CHEF")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(dish(first).path("notes").asText()).contains("大米");
        assertThat(dish(first).path("version").asLong()).isGreaterThan(beforeFirst.path("version").asLong());
        assertThat(menuIds()).containsExactly(first, second);
        assertThat(save(List.of(first, second), saved).path("revision").asLong()).isEqualTo(2);
    }

    @Test void newDishesAppendAndEditsAndDeletesDoNotScrambleSavedOrder() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        save(List.of(second, first), order());
        long third = create("汤");
        long fourth = create("水果");
        assertThat(menuIds()).containsExactly(second, first, third, fourth);
        JsonNode editing = dish(third);
        mvc.perform(multipart("/api/chef/dishes").with(user("chef").roles("CHEF")).with(csrf())
                        .param("operation", "UPDATE").param("dishId", Long.toString(third))
                        .param("expectedVersion", editing.path("version").asText())
                        .param("name", "南瓜汤").param("notes", "• 南瓜"))
                .andExpect(status().isOk());
        assertThat(menuIds()).containsExactly(second, first, third, fourth);
        deleteDish(first);
        assertThat(menuIds()).containsExactly(second, third, fourth);
        assertThat(ids(order().path("dishIds"))).containsExactly(second, third, fourth);
    }

    @Test void anEmptyMenuCanBeSavedAndLaterDishesAppendInCreationOrder() throws Exception {
        JsonNode saved = save(List.of(), order());
        assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(menuIds()).isEmpty();
        long first = create("米饭");
        long second = create("青菜");
        assertThat(menuIds()).containsExactly(first, second);
    }

    @Test void onlyChefCanReadAndSaveOrderAndWritesRequireCsrf() throws Exception {
        String body = "{\"dishIds\":[],\"expectedDishIds\":[],\"expectedRevision\":0}";
        mvc.perform(get("/api/chef/dishes/order")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chef/dishes/order").with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/chef/dishes/order").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/chef/dishes/order").with(user("guest").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/chef/dishes/order").with(user("chef").roles("CHEF"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(order().path("revision").asLong()).isZero();
    }

    @Test void malformedOrdersCannotChangeTheMenu() throws Exception {
        long first = create("米饭");
        JsonNode original = order();
        for (String body : List.of(
                "{}",
                "{\"dishIds\":null,\"expectedDishIds\":[],\"expectedRevision\":0}",
                "{\"dishIds\":[],\"expectedDishIds\":null,\"expectedRevision\":0}",
                "{\"dishIds\":[],\"expectedDishIds\":[],\"expectedRevision\":null}",
                "{\"dishIds\":[],\"expectedDishIds\":[],\"expectedRevision\":-1}",
                "{\"dishIds\":[0],\"expectedDishIds\":[0],\"expectedRevision\":0}",
                "{\"dishIds\":[-1],\"expectedDishIds\":[-1],\"expectedRevision\":0}",
                "{\"dishIds\":[null],\"expectedDishIds\":[null],\"expectedRevision\":0}",
                "{\"dishIds\":[" + first + "," + first + "],\"expectedDishIds\":[" + first + "],\"expectedRevision\":0}",
                "{\"dishIds\":[" + first + "],\"expectedDishIds\":[" + first + "," + first + "],\"expectedRevision\":0}",
                "{\"dishIds\":[],\"expectedDishIds\":[" + first + "],\"expectedRevision\":0}")) {
            mvc.perform(orderRequest(body)).andExpect(status().isBadRequest());
            assertThat(order()).isEqualTo(original);
            assertThat(menuIds()).containsExactly(first);
        }
    }

    @Test void staleRevisionAndMembershipAreConflictsAndNeverOverwriteNewerChanges() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        JsonNode beforeSave = order();
        JsonNode saved = save(List.of(first, second), beforeSave);
        mvc.perform(orderRequest(body(List.of(second, first), beforeSave))).andExpect(status().isConflict());
        assertThat(order()).isEqualTo(saved);

        long third = create("汤");
        mvc.perform(orderRequest(body(List.of(second, first), saved))).andExpect(status().isConflict());
        assertThat(menuIds()).containsExactly(first, second, third);
        JsonNode beforeDelete = order();
        deleteDish(second);
        mvc.perform(orderRequest(body(List.of(third, second, first), beforeDelete))).andExpect(status().isConflict());
        assertThat(menuIds()).containsExactly(first, third);
        assertThat(order().path("revision")).isEqualTo(saved.path("revision"));
    }

    @Test void concurrentSavesAllowOnlyOneWinner() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        long third = create("汤");
        JsonNode original = order();
        List<Long> left = List.of(first, second, third);
        List<Long> right = List.of(second, third, first);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> saveWhenReleased(body(left, original), ready, start));
            var two = executor.submit(() -> saveWhenReleased(body(right, original), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
            JsonNode winner = json(results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow());
            assertThat(order()).isEqualTo(winner);
            assertThat(menuIds()).isEqualTo(ids(winner.path("dishIds")));
            assertThat(winner.path("revision").asLong()).isEqualTo(original.path("revision").asLong() + 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void concurrentNewDishIsNeverLostBySavingAnOlderSnapshot() throws Exception {
        long first = create("米饭");
        long second = create("青菜");
        JsonNode original = order();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var saving = executor.submit(() -> saveWhenReleased(body(List.of(first, second), original), ready, start));
            var creating = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                return create("汤");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult result = saving.get(20, TimeUnit.SECONDS);
            long third = creating.get(20, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isIn(200, 409);
            assertThat(menuIds()).containsExactlyInAnyOrder(first, second, third);
            if (result.getResponse().getStatus() == 200) {
                assertThat(menuIds()).containsExactly(first, second, third);
                assertThat(order().path("revision").asLong()).isEqualTo(1);
            } else {
                assertThat(menuIds()).containsExactly(third, second, first);
                assertThat(order().path("revision").asLong()).isZero();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long create(String name) throws Exception {
        return json(mvc.perform(multipart("/api/chef/dishes").with(user("chef").roles("CHEF")).with(csrf())
                .param("operation", "CREATE").param("name", name).param("notes", "• 原料\n// 准备"))
                .andExpect(status().isOk()).andReturn()).path("dishId").asLong();
    }

    private void deleteDish(long id) throws Exception {
        JsonNode deleting = dish(id);
        mvc.perform(multipart("/api/chef/dishes").with(user("chef").roles("CHEF")).with(csrf())
                        .param("operation", "DELETE").param("dishId", Long.toString(id))
                        .param("expectedVersion", deleting.path("version").asText()))
                .andExpect(status().isOk());
    }

    private JsonNode dish(long id) throws Exception {
        for (JsonNode dish : menu()) if (dish.path("id").asLong() == id) return dish;
        throw new AssertionError("Dish was not found: " + id);
    }

    private JsonNode menu() throws Exception {
        return json(mvc.perform(get("/api/menu")).andExpect(status().isOk()).andReturn());
    }

    private List<Long> menuIds() throws Exception {
        List<Long> result = new ArrayList<>();
        for (JsonNode dish : menu()) result.add(dish.path("id").asLong());
        return result;
    }

    private JsonNode order() throws Exception {
        return json(mvc.perform(get("/api/chef/dishes/order").with(user("chef").roles("CHEF")))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode save(List<Long> desired, JsonNode snapshot) throws Exception {
        return json(mvc.perform(orderRequest(body(desired, snapshot))).andExpect(status().isOk()).andReturn());
    }

    private String body(List<Long> desired, JsonNode snapshot) throws Exception {
        return mapper.writeValueAsString(Map.of("dishIds", desired, "expectedDishIds", ids(snapshot.path("dishIds")),
                "expectedRevision", snapshot.path("revision").asLong()));
    }

    private MockHttpServletRequestBuilder orderRequest(String body) {
        return put("/api/chef/dishes/order").with(user("chef").roles("CHEF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult saveWhenReleased(String body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
        return mvc.perform(orderRequest(body)).andReturn();
    }

    private List<Long> ids(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asLong()));
        return result;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
