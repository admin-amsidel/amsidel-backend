package com.amsidel.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off schema change that ddl-auto=update cannot make: the first attendance version allowed one row per person
 * and day (uk_attendance_employee_day); field days now have several entries (uk_attendance_entry). Dropping the
 * old rule is safe to repeat and does nothing once it is gone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceSchemaFix implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("alter table attendance drop constraint if exists uk_attendance_employee_day");
        } catch (RuntimeException e) {
            log.warn("Could not drop the old one-check-in-a-day rule on attendance: {}", e.getMessage());
        }
    }
}
