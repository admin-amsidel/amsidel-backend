package com.amsidel.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema changes that ddl-auto=update cannot make, safe to repeat:
 * <ul>
 *   <li>the first attendance version allowed one row per person and day (uk_attendance_employee_day); field days
 *   now have several entries (uk_attendance_entry), so the old rule is dropped;</li>
 *   <li>performance ratings and skill levels were whole stars; they are half stars now (numeric(3,1)), and the
 *   existing values are kept (4 becomes 4.0).</li>
 * </ul>
 * Each change that fails (e.g. a table not made yet) is only logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaFixes implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    private static final String[] HALF_STAR_COLUMNS = {
            "goals.self_rating", "goals.manager_rating", "review_skills.self_level", "review_skills.manager_level"};

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("alter table attendance drop constraint if exists uk_attendance_employee_day");
        } catch (RuntimeException e) {
            log.warn("Could not drop the old one-check-in-a-day rule on attendance: {}", e.getMessage());
        }
        if (!postgres()) {
            return; // the test database is made fresh with the right types
        }
        for (String column : HALF_STAR_COLUMNS) {
            String[] tc = column.split("\\.");
            try {
                String type = jdbc.queryForObject("select data_type from information_schema.columns where table_name = ? and column_name = ?",
                        String.class, tc[0], tc[1]);
                if (type != null && type.toLowerCase().contains("int")) {
                    jdbc.execute("alter table " + tc[0] + " alter column " + tc[1] + " type numeric(3,1)");
                    log.info("{} now takes half stars", column);
                }
            } catch (RuntimeException e) {
                log.warn("Could not change {} to half stars: {}", column, e.getMessage());
            }
        }
    }

    private boolean postgres() {
        try {
            return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) ->
                    c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
