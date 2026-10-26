package com.amsidel.backend.config;

import com.amsidel.backend.entity.TradeSubject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Schema changes that ddl-auto=update cannot make, safe to repeat:
 * <ul>
 *   <li>the first attendance version allowed one row per person and day (uk_attendance_employee_day); field days
 *   now have several entries (uk_attendance_entry), so the old rule is dropped;</li>
 *   <li>performance ratings and skill levels were whole stars; they are half stars now (numeric(3,1)), and the
 *   existing values are kept (4 becomes 4.0).</li>
 *   <li>a table made when an enum had fewer values keeps a check that refuses the new ones (e.g. trade_history made
 *   before bank statements were recorded in it): such a check is made again from the enum.</li>
 * </ul>
 * Each change that fails (e.g. a table not made yet) is only logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaFixes implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    /** Enum columns of tables that may have been made before the enum grew: table, column, enum. */
    private static final List<EnumColumn> ENUM_COLUMNS = List.of(
            new EnumColumn("trade_history", "subject_type", TradeSubject.class),
            new EnumColumn("news_posts", "audience", com.amsidel.backend.entity.NewsAudience.class),
            new EnumColumn("online_payments", "method", com.amsidel.backend.entity.OnlineMethod.class),
            new EnumColumn("online_fees", "method", com.amsidel.backend.entity.OnlineMethod.class),
            new EnumColumn("brands", "currency", com.amsidel.backend.entity.CurrencyCode.class));

    /** Columns made optional after they were created as required (ddl update never relaxes them): manual UPI has no Razorpay order. */
    /** Columns that held an enum and now hold a code from a table: their old allowed-values check goes. */
    private static final String[][] NO_LONGER_ENUM = {{"money_entries", "category"}};

    private static final String[] NOW_OPTIONAL = {"online_payments.gateway_key_id", "online_payments.gateway_order_id"};

    /** Done once (kept in doc_sequences): news saved as "customers" when that meant the website became Public. */
    private static final String NEWS_PUBLIC_DONE = "SETUP-NEWS-PUBLIC";

    private record EnumColumn(String table, String column, Class<? extends Enum<?>> type) {
    }

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
        ENUM_COLUMNS.forEach(this::enumCheck);
        for (String[] tc : NO_LONGER_ENUM) {
            try {
                List<Map<String, Object>> checks = jdbc.queryForList(
                        "select con.conname as name from pg_constraint con join pg_class rel on rel.oid = con.conrelid"
                                + " where rel.relname = ? and con.contype = 'c' and pg_get_constraintdef(con.oid) like ?", tc[0], "%" + tc[1] + "%");
                for (Map<String, Object> c : checks) {
                    jdbc.execute("alter table " + tc[0] + " drop constraint if exists \"" + c.get("name") + "\"");
                    log.info("{}.{} now takes any category code", tc[0], tc[1]);
                }
            } catch (RuntimeException e) {
                log.warn("Could not free {}.{}: {}", tc[0], tc[1], e.getMessage());
            }
        }
        for (String column : NOW_OPTIONAL) {
            String[] tc = column.split("\\.");
            try {
                String nullable = jdbc.queryForObject("select is_nullable from information_schema.columns where table_name = ? and column_name = ?",
                        String.class, tc[0], tc[1]);
                if ("NO".equals(nullable)) {
                    jdbc.execute("alter table " + tc[0] + " alter column " + tc[1] + " drop not null");
                    log.info("{} is now optional", column);
                }
            } catch (RuntimeException e) {
                log.warn("Could not make {} optional: {}", column, e.getMessage());
            }
        }
        newsPublic();
    }

    /**
     * Until the Public choice came, "customers" news was the website's; now Customers is for staff to pass on and
     * Public is the website. The news saved before then moves to Public once, so it stays on the website.
     */
    private void newsPublic() {
        try {
            Integer done = jdbc.queryForObject("select count(*) from doc_sequences where prefix = ?", Integer.class, NEWS_PUBLIC_DONE);
            if (done != null && done > 0) {
                return;
            }
            int moved = jdbc.update("update news_posts set audience = 'PUBLIC' where audience = 'CUSTOMERS'");
            jdbc.update("insert into doc_sequences (prefix, last_number) values (?, 1)", NEWS_PUBLIC_DONE);
            if (moved > 0) {
                log.info("{} news item(s) for the website are now Public", moved);
            }
        } catch (RuntimeException e) {
            log.warn("Could not move the website news to Public: {}", e.getMessage());
        }
    }

    /** Makes the column's check allow every value of its enum, when one is missing from it. */
    private void enumCheck(EnumColumn ec) {
        try {
            List<String> values = Arrays.stream(ec.type().getEnumConstants()).map(Enum::name).toList();
            List<Map<String, Object>> checks = jdbc.queryForList(
                    "select con.conname as name, pg_get_constraintdef(con.oid) as def from pg_constraint con"
                            + " join pg_class rel on rel.oid = con.conrelid where rel.relname = ? and con.contype = 'c'"
                            + " and pg_get_constraintdef(con.oid) like ?", ec.table(), "%" + ec.column() + "%");
            boolean current = !checks.isEmpty() && checks.stream().allMatch(c -> values.stream().allMatch(v -> String.valueOf(c.get("def")).contains("'" + v + "'")));
            if (current) {
                return;
            }
            for (Map<String, Object> c : checks) {
                jdbc.execute("alter table " + ec.table() + " drop constraint if exists \"" + c.get("name") + "\"");
            }
            String list = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(","));
            jdbc.execute("alter table " + ec.table() + " add constraint ck_" + ec.table() + "_" + ec.column()
                    + " check (" + ec.column() + " in (" + list + "))");
            log.info("{}.{} now allows {}", ec.table(), ec.column(), list);
        } catch (RuntimeException e) {
            log.warn("Could not update the allowed values of {}.{}: {}", ec.table(), ec.column(), e.getMessage());
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
