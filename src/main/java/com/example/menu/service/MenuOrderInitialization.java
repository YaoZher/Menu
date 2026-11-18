package com.example.menu.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(-1)
public class MenuOrderInitialization implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public MenuOrderInitialization(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        // H2 MERGE's matched branch is deliberately absent: a restart never resets saved order.
        jdbc.update("""
                merge into menu_orders target using (values (1)) source(id)
                on target.id = source.id
                when not matched then insert (id, ordered_ids, revision, lock_version)
                values (source.id, null, 0, 0)
                """);
    }
}
