package com.example.menu;

import com.example.menu.domain.*;
import com.example.menu.service.TagCatalogMigration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:tags;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class TagIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DishRepository dishes;
    @Autowired SubmissionRepository submissions;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TagCatalogMigration migration;

    @Test void catalogIsPublicGroupedAndContainsRequestedLabels() throws Exception {
        JsonNode catalog = json(mvc.perform(get("/api/tags")).andExpect(status().isOk()).andReturn());
        assertThat(catalog.size()).isGreaterThanOrEqualTo(5);
        for (String name : List.of("主食", "碳水", "蛋白质", "前菜", "水果", "汤", "烧烤", "高油", "未分类")) {
            assertThat(tagId(name)).isPositive();
        }
        assertThat(categoryId("烹饪方式")).isPositive();
        mvc.perform(post("/api/chef/tag-categories").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"访客分类\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chef/tags").with(user("visitor").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":1,\"name\":\"访客标签\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void chefCreatesClassifiedTagsAndValidationRejectsDuplicates() throws Exception {
        String name = "分类" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode category = write(post("/api/chef/tag-categories").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new TagName(name))), 200);
        long id = category.path("id").asLong();
        JsonNode tag = write(post("/api/chef/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + id + ",\"name\":\"地方菜\"}"), 200);
        assertThat(tag.path("categoryId").asLong()).isEqualTo(id);
        assertThat(tag.path("categoryName").asText()).isEqualTo(name);
        write(post("/api/chef/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + id + ",\"name\":\" 地方菜 \"}"), 409);
        write(post("/api/chef/tag-categories").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new TagName(name))), 409);
        mvc.perform(post("/api/chef/tag-categories").with(user("chef").roles("CHEF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无验证分类\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chef/tags").with(user("chef").roles("CHEF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":" + id + ",\"name\":\"无验证标签\"}"))
                .andExpect(status().isForbidden());
        for (String invalid : List.of("", " ", "名".repeat(31), "坏\n名称", "坏\u200b名称")) {
            write(post("/api/chef/tag-categories").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(new TagName(invalid))), 400);
            write(post("/api/chef/tags").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(new TagCreate(id, invalid))), 400);
        }
        write(post("/api/chef/tags").contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":999999,\"name\":\"不存在\"}"), 400);
    }

    @Test void visitorTagsWaitForReviewAndBothSnapshotsPersist() throws Exception {
        long protein = tagId("蛋白质"), roast = tagId("烧烤"), soup = tagId("汤");
        JsonNode pending = submit(multipart("/api/submissions").param("operation", "CREATE").param("name", uniqueName())
                .param("tagIds", "[" + protein + "," + roast + "]"));
        JsonNode record = findSubmission(pending.path("id").asLong());
        assertThat(record.path("before").isNull()).isTrue();
        assertThat(record.path("after").path("tags").size()).isEqualTo(2);
        JsonNode approved = approve(pending.path("id").asLong());
        JsonNode dish = findDish(approved.path("dishId").asLong());
        assertThat(dish.path("tags").get(0).path("id").asLong()).isEqualTo(protein);
        JsonNode update = submit(multipart("/api/submissions").param("operation", "UPDATE")
                .param("dishId", dish.path("id").asText()).param("expectedVersion", dish.path("version").asText())
                .param("name", dish.path("name").asText()).param("tagIds", "[" + soup + "]"));
        JsonNode stored = findSubmission(update.path("id").asLong());
        assertThat(stored.path("before").path("tags").size()).isEqualTo(2);
        assertThat(stored.path("after").path("tags").get(0).path("name").asText()).isEqualTo("汤");
        write(post("/api/chef/submissions/{id}/reject", update.path("id").asLong()).contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        assertThat(findDish(dish.path("id").asLong()).path("tags")).isEqualTo(dish.path("tags"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.flush(); entityManager.clear();
            assertThat(submissions.findById(update.path("id").asLong()).orElseThrow().getBeforeSnapshot().getTags()).hasSize(2);
            assertThat(submissions.findById(update.path("id").asLong()).orElseThrow().getAfterSnapshot().getTags().getFirst().name()).isEqualTo("汤");
        });
    }

    @Test void directEditingPreservesOmittedTagsDeduplicatesAndDefaultsEmptySelection() throws Exception {
        long carb = tagId("碳水"), staple = tagId("主食"), unknown = tagId("未分类");
        JsonNode created = write(multipart("/api/chef/dishes").param("operation", "CREATE").param("name", uniqueName())
                .param("tagIds", "[" + (carb + ",").repeat(13) + staple + "," + unknown + "]"), 200);
        JsonNode dish = findDish(created.path("dishId").asLong());
        assertThat(dish.path("tags").size()).isEqualTo(2);
        assertThat(dish.path("tags").get(0).path("categoryName").asText()).isEqualTo("营养构成");
        JsonNode updated = write(change(dish).param("notes", "• 米 1 碗"), 200);
        assertThat(updated.path("after").path("tags")).isEqualTo(dish.path("tags"));
        write(change(dish).param("tagIds", "[]"), 409);
        JsonNode current = findDish(dish.path("id").asLong());
        write(change(current).param("tagIds", "[]"), 200);
        JsonNode reset = findDish(dish.path("id").asLong());
        assertThat(reset.path("tags").size()).isEqualTo(1);
        assertThat(reset.path("tags").get(0).path("name").asText()).isEqualTo("未分类");
        JsonNode withoutTags = write(multipart("/api/chef/dishes").param("operation", "CREATE").param("name", uniqueName()), 200);
        assertThat(withoutTags.path("after").path("tags").get(0).path("name").asText()).isEqualTo("未分类");
    }

    @Test void rejectsUnknownMalformedAndExcessiveSelections() throws Exception {
        for (String invalid : List.of("", "null", "{}", "[\"1\"]", "[true]", "[-1]", "[1.5]", "[999999]", "[9223372036854775808]",
                "[1,2,3,4,5,6,7,8,9,10,11,12,13]", " ".repeat(2049))) {
            mvc.perform(multipart("/api/submissions").param("operation", "CREATE").param("name", uniqueName())
                    .param("tagIds", invalid).with(csrf())).andExpect(status().isBadRequest());
        }
    }

    @Test void migrationOnlyBackfillsTagsAndOldPendingRequestsRemainApprovable() throws Exception {
        Instant timestamp = Instant.parse("2025-06-01T00:00:00Z");
        long[] ids = new TransactionTemplate(transactionManager).execute(status -> {
            Dish old = dishes.saveAndFlush(new Dish(new DishSnapshot(uniqueName(), null, null, "• 葱"), timestamp));
            Submission update = submissions.saveAndFlush(new Submission(ChangeOperation.UPDATE, old.getId(), old.getVersion(),
                    old.snapshot(), new DishSnapshot(old.getName(), null, null, "• 葱\n• 姜"), timestamp));
            Submission create = submissions.saveAndFlush(new Submission(ChangeOperation.CREATE, null, null,
                    null, new DishSnapshot(uniqueName(), null, null, null), timestamp));
            Submission delete = submissions.saveAndFlush(new Submission(ChangeOperation.DELETE, old.getId(), old.getVersion(), old.snapshot(), null, timestamp));
            return new long[] {old.getId(), update.getId(), create.getId(), delete.getId()};
        });
        jdbc.update("update dishes set tags = null where id = ?", ids[0]);
        for (long id : new long[] {ids[1], ids[2], ids[3]}) jdbc.update("update submissions set before_tags = null, after_tags = null where id = ?", id);
        int categoryCount = jdbc.queryForObject("select count(*) from tag_categories", Integer.class);
        int tagCount = jdbc.queryForObject("select count(*) from dish_tags", Integer.class);
        assertThat(migration.seedAndBackfill()).isEqualTo(5);
        assertThat(migration.seedAndBackfill()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tag_categories", Integer.class)).isEqualTo(categoryCount);
        assertThat(jdbc.queryForObject("select count(*) from dish_tags", Integer.class)).isEqualTo(tagCount);
        JsonNode old = findDish(ids[0]);
        assertThat(old.path("version").asLong()).isZero();
        assertThat(old.path("updatedAt").asText()).isEqualTo(timestamp.toString());
        assertThat(old.path("notes").asText()).isEqualTo("• 葱");
        assertThat(old.path("tags").get(0).path("name").asText()).isEqualTo("未分类");
        assertThat(findSubmission(ids[2]).path("before").isNull()).isTrue();
        assertThat(findSubmission(ids[3]).path("after").isNull()).isTrue();
        approve(ids[1]);
        assertThat(findDish(ids[0]).path("notes").asText()).contains("姜");
        assertThat(findDish(ids[0]).path("tags").get(0).path("name").asText()).isEqualTo("未分类");
    }

    private MockHttpServletRequestBuilder change(JsonNode dish) {
        return multipart("/api/chef/dishes").param("operation", "UPDATE").param("dishId", dish.path("id").asText())
                .param("expectedVersion", dish.path("version").asText()).param("name", dish.path("name").asText());
    }
    private long tagId(String name) throws Exception {
        for (JsonNode group : json(mvc.perform(get("/api/tags")).andExpect(status().isOk()).andReturn())) {
            for (JsonNode tag : group.path("tags")) if (tag.path("name").asText().equals(name)) return tag.path("id").asLong();
        }
        throw new AssertionError("Tag missing: " + name);
    }
    private long categoryId(String name) throws Exception {
        for (JsonNode group : json(mvc.perform(get("/api/tags")).andExpect(status().isOk()).andReturn())) {
            if (group.path("name").asText().equals(name)) return group.path("id").asLong();
        }
        throw new AssertionError("Category missing: " + name);
    }
    private String uniqueName() { return "标签菜品-" + UUID.randomUUID(); }
    private JsonNode submit(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request.with(csrf())).andExpect(status().isAccepted()).andReturn());
    }
    private JsonNode write(MockHttpServletRequestBuilder request, int status) throws Exception {
        return json(mvc.perform(request.with(user("chef").roles("CHEF")).with(csrf())).andExpect(status().is(status)).andReturn());
    }
    private JsonNode approve(long id) throws Exception {
        return write(post("/api/chef/submissions/{id}/approve", id).contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
    }
    private JsonNode findDish(long id) throws Exception {
        for (JsonNode dish : json(mvc.perform(get("/api/menu")).andExpect(status().isOk()).andReturn())) {
            if (dish.path("id").asLong() == id) return dish;
        }
        throw new AssertionError("Dish missing");
    }
    private JsonNode findSubmission(long id) throws Exception {
        for (JsonNode record : json(mvc.perform(get("/api/chef/submissions?status=ALL").with(user("chef").roles("CHEF")))
                .andExpect(status().isOk()).andReturn())) if (record.path("id").asLong() == id) return record;
        throw new AssertionError("Submission missing");
    }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private record TagName(String name) { }
    private record TagCreate(Long categoryId, String name) { }
}
