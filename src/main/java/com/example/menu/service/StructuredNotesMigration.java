package com.example.menu.service;

import com.example.menu.domain.NoteLines;
import com.example.menu.domain.NoteLinesConverter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StructuredNotesMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final NoteLinesConverter converter = new NoteLinesConverter();

    public StructuredNotesMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) { backfill(); }

    @Transactional
    public int backfill() {
        // Only populate new columns: existing versions/timestamps and pending approvals must stay valid.
        return backfillColumn("dishes", "notes", "note_lines", "name")
                + backfillColumn("submissions", "before_notes", "before_note_lines", "before_name")
                + backfillColumn("submissions", "after_notes", "after_note_lines", "after_name");
    }

    private int backfillColumn(String table, String notes, String structured, String snapshotName) {
        var rows = jdbc.query("select id, " + notes + " from " + table
                        + " where " + structured + " is null and " + snapshotName + " is not null",
                (result, index) -> new LegacyNotes(result.getLong(1), result.getString(2)));
        int changed = 0;
        for (LegacyNotes row : rows) {
            changed += jdbc.update("update " + table + " set " + structured + " = ? where id = ? and " + structured + " is null",
                    converter.convertToDatabaseColumn(NoteLines.parse(row.notes())), row.id());
        }
        return changed;
    }

    private record LegacyNotes(long id, String notes) { }
}
