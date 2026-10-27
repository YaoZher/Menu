package com.example.menu;

import com.example.menu.domain.*;
import com.example.menu.service.MenuService;
import com.example.menu.service.StructuredNotesMigration;
import com.example.menu.service.SubmissionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.menu.domain.NoteLine.Type.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:structurednotes;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.chef.password=Test-password-2026!",
    "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class StructuredNotesTest {
    @Autowired DishRepository dishes;
    @Autowired SubmissionRepository submissions;
    @Autowired MenuService service;
    @Autowired StructuredNotesMigration migration;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void classifiesOnlyLinePrefixesAndPreservesOrder() {
        assertThat(NoteLines.parse("\r\n  • 西红柿 2 个 \r\n·鸡蛋 3 个\r// 大火翻炒\n少盐\nhttps://example.com/video\n调味 // 酌情\n• \n//\n·\n"))
                .containsExactly(new NoteLine(INGREDIENT, "西红柿 2 个"),
                        new NoteLine(INGREDIENT, "鸡蛋 3 个"), new NoteLine(STEP, "大火翻炒"),
                        new NoteLine(NOTE, "少盐"), new NoteLine(NOTE, "https://example.com/video"),
                        new NoteLine(NOTE, "调味 // 酌情"));
        assertThat(NoteLines.parse(null)).isEmpty();
        assertThat(NoteLines.parse(" \r\n\t")).isEmpty();
    }

    @Test void persistsStructuredDishAndBothSnapshotsAcrossFlushAndClear() {
        String notes = "• 豆腐\n// 煎至金黄\n少盐";
        List<NoteLine> expected = List.of(new NoteLine(INGREDIENT, "豆腐"), new NoteLine(STEP, "煎至金黄"), new NoteLine(NOTE, "少盐"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Dish dish = dishes.saveAndFlush(new Dish(new DishSnapshot(uniqueName(), null, null, notes), Instant.now()));
            Submission submission = submissions.saveAndFlush(new Submission(ChangeOperation.UPDATE, dish.getId(), dish.getVersion(),
                    dish.snapshot(), new DishSnapshot(dish.getName(), null, null, "· 青葱\n// 拌匀"), Instant.now()));
            long dishId = dish.getId();
            long submissionId = submission.getId();
            entityManager.clear();
            Dish reloaded = dishes.findById(dishId).orElseThrow();
            Submission reloadedSubmission = submissions.findById(submissionId).orElseThrow();
            assertThat(reloaded.getNotes()).isEqualTo(notes);
            assertThat(reloaded.getNoteLines()).containsExactlyElementsOf(expected);
            assertThat(reloadedSubmission.getBeforeSnapshot().getNoteLines()).containsExactlyElementsOf(expected);
            assertThat(reloadedSubmission.getAfterSnapshot().getNoteLines())
                    .containsExactly(new NoteLine(INGREDIENT, "青葱"), new NoteLine(STEP, "拌匀"));
            assertThat(jdbc.queryForObject("select note_lines from dishes where id = ?", String.class, dishId))
                    .contains("INGREDIENT", "豆腐", "STEP", "煎至金黄");
            assertThat(jdbc.queryForObject("select before_note_lines from submissions where id = ?", String.class, submissionId))
                    .contains("豆腐");
            assertThat(jdbc.queryForObject("select after_note_lines from submissions where id = ?", String.class, submissionId))
                    .contains("青葱");
        });
    }

    @Test void exposesStructuredLinesForPendingReviewAndPublishedMenu() throws Exception {
        String name = uniqueName();
        String notes = "• 鸡蛋 2 个\n// 打散\n不放辣椒";
        JsonNode receipt = mapper.readTree(mvc.perform(multipart("/api/submissions").with(csrf())
                        .param("operation", "CREATE").param("name", name).param("notes", notes))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        long id = receipt.path("id").asLong();
        JsonNode pending = find(mapper.readTree(mvc.perform(get("/api/chef/submissions?status=ALL")
                        .with(user("chef").roles("CHEF"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()), id);
        assertThat(pending.path("before").isNull()).isTrue();
        assertThat(pending.path("after").path("notes").asText()).isEqualTo(notes);
        assertThat(pending.path("after").path("noteLines").get(0).path("type").asText()).isEqualTo("INGREDIENT");
        assertThat(pending.path("after").path("noteLines").get(1).path("text").asText()).isEqualTo("打散");
        long dishId = service.approve(id, null, "chef").dishId();
        JsonNode published = find(mapper.readTree(mvc.perform(get("/api/menu")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()), dishId);
        assertThat(published.path("noteLines")).isEqualTo(pending.path("after").path("noteLines"));
    }

    @Test void approvalUpdatesStructuredNotesWhileRejectionLeavesThemUntouched() {
        Dish dish = dishes.saveAndFlush(new Dish(new DishSnapshot(uniqueName(), null, null, "• 葱\n// 切段"), Instant.now()));
        long update = submitUpdate(dish, "• 姜\n// 切丝");
        assertThat(dishes.findById(dish.getId()).orElseThrow().getNoteLines().get(0).text()).isEqualTo("葱");
        service.approve(update, null, "chef");
        Dish approved = dishes.findById(dish.getId()).orElseThrow();
        assertThat(approved.getNoteLines()).containsExactly(new NoteLine(INGREDIENT, "姜"), new NoteLine(STEP, "切丝"));
        long rejected = submitUpdate(approved, "• 蒜\n// 拍碎");
        service.reject(rejected, "保留原备菜方法", "chef");
        assertThat(dishes.findById(dish.getId()).orElseThrow().getNoteLines()).isEqualTo(approved.getNoteLines());
        long cleared = submitUpdate(approved, null);
        service.approve(cleared, null, "chef");
        Dish empty = dishes.findById(dish.getId()).orElseThrow();
        assertThat(empty.getNotes()).isNull();
        assertThat(empty.getNoteLines()).isEmpty();
        assertThat(jdbc.queryForObject("select note_lines from dishes where id = ?", String.class, dish.getId())).isEqualTo("[]");
    }

    @Test void backfillIsIdempotentKeepsVersionsAndAbsentSnapshotsAndAllowsOldApproval() {
        Instant updatedAt = Instant.parse("2026-01-01T10:00:00Z");
        Dish dish = dishes.saveAndFlush(new Dish(new DishSnapshot(uniqueName(), null, null, "• 土豆\n// 切块"), updatedAt));
        long updateId = submitUpdate(dish, "• 土豆 2 个\n// 炖熟");
        Submission created = submissions.saveAndFlush(new Submission(ChangeOperation.CREATE, null, null, null,
                new DishSnapshot(uniqueName(), null, null, null), Instant.now()));
        Submission deletion = submissions.saveAndFlush(new Submission(ChangeOperation.DELETE, dish.getId(), dish.getVersion(),
                dish.snapshot(), null, Instant.now()));
        jdbc.update("update dishes set note_lines = null where id = ?", dish.getId());
        jdbc.update("update submissions set before_note_lines = null, after_note_lines = null where id in (?, ?, ?)",
                updateId, created.getId(), deletion.getId());

        assertThat(migration.backfill()).isEqualTo(5);
        Dish migrated = dishes.findById(dish.getId()).orElseThrow();
        assertThat(migrated.getVersion()).isEqualTo(dish.getVersion());
        assertThat(migrated.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(migrated.getNoteLines()).containsExactly(new NoteLine(INGREDIENT, "土豆"), new NoteLine(STEP, "切块"));
        assertThat(migration.backfill()).isZero();
        assertThat(submissions.findById(created.getId()).orElseThrow().getBeforeSnapshot()).isNull();
        assertThat(submissions.findById(created.getId()).orElseThrow().getAfterSnapshot().getNoteLines()).isEmpty();
        assertThat(submissions.findById(deletion.getId()).orElseThrow().getAfterSnapshot()).isNull();
        assertThat(jdbc.queryForObject("select before_note_lines from submissions where id = ?", String.class, created.getId())).isNull();
        assertThat(jdbc.queryForObject("select after_note_lines from submissions where id = ?", String.class, deletion.getId())).isNull();
        assertThat(submissions.findById(updateId).orElseThrow().getBaseVersion()).isEqualTo(dish.getVersion());
        service.approve(updateId, null, "chef");
        assertThat(dishes.findById(dish.getId()).orElseThrow().getNoteLines())
                .containsExactly(new NoteLine(INGREDIENT, "土豆 2 个"), new NoteLine(STEP, "炖熟"));
    }

    private long submitUpdate(Dish dish, String notes) {
        return service.submit(new SubmissionCommand("UPDATE", dish.getId().toString(), Long.toString(dish.getVersion()),
                dish.getName(), null, notes, "KEEP", null)).id();
    }

    private static String uniqueName() { return "备菜-" + System.nanoTime(); }

    private static JsonNode find(JsonNode values, long id) {
        for (JsonNode value : values) if (value.path("id").asLong() == id) return value;
        throw new AssertionError("Missing item " + id);
    }
}
