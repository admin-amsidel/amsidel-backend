/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When the scheduled jobs run, and whether they may. Each job has two rows in the configurations table: its cron (over the
 * properties file's), and on or off. A job runs when all three say so: this machine (app.schedulers.enabled), the
 * SCHEDULERS_ENABLED row for all jobs, and the job's own row. The rows are shared by every server on the same database;
 * the property is the only thing that stops one machine alone, so here it is not overridden by the table.
 */
@Slf4j
@Component
public class SchedulerSwitch implements KnownSettings {

    public static final String ALL = "SCHEDULERS_ENABLED";
    private static final Duration SHORTEST_GAP = Duration.ofMinutes(1);
    private static final DateTimeFormatter NEXT = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    public enum Job {
        PRODUCT_COUNT("SCHEDULER_PRODUCT_COUNT", "Product count check"),
        SESSION_CHECK("SCHEDULER_SESSION_CHECK", "Session check: marks expired sessions on the employee"),
        LEAVE_EXPIRY("SCHEDULER_LEAVE_EXPIRY", "Leave plan expiry notice"),
        CHAT_CLEANUP("SCHEDULER_CHAT_CLEANUP", "Team chat clean-up of old messages"),
        CHAT_REPORT("SCHEDULER_CHAT_REPORT", "Team chat usage line in the server log");

        private final String prefix;
        private final String label;

        Job(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        public String code() {
            return prefix + "_ENABLED";
        }

        public String cronCode() {
            return prefix + "_CRON";
        }
    }

    private final ConfigValues config;
    private final boolean thisMachine;
    private final ZoneId zone;
    private final Map<Job, String> fallbacks;
    private final Map<Job, Boolean> lastSaid = new ConcurrentHashMap<>();

    public SchedulerSwitch(ConfigValues config, @Value("${app.schedulers.enabled}") boolean thisMachine, @Value("${app.leave.zone}") ZoneId zone,
                           @Value("${app.scheduler.product-count.cron}") String productCount,
                           @Value("${app.scheduler.session-check.cron}") String sessionCheck,
                           @Value("${app.leave.expiry-cron:0 0 8 * * *}") String leaveExpiry,
                           @Value("${app.chat.clean-cron:0 0 7 * * *}") String chatCleanup,
                           @Value("${app.scheduler.chat-report.cron}") String chatReport) {
        this.config = config;
        this.thisMachine = thisMachine;
        this.zone = zone;
        this.fallbacks = Map.of(Job.PRODUCT_COUNT, productCount, Job.SESSION_CHECK, sessionCheck, Job.LEAVE_EXPIRY, leaveExpiry,
                Job.CHAT_CLEANUP, chatCleanup, Job.CHAT_REPORT, chatReport);
    }

    /** On is always "true" whatever this machine's property says: the row is shared, and one machine must not stop the others. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void seed() {
        config.seed(ALL, "All scheduled jobs: true = may run, false = all stopped", "true");
        for (Job j : Job.values()) {
            config.seed(j.code(), j.label + ": true = runs, false = stopped", "true");
            config.seed(j.cronCode(), j.label + ": when it runs, as a cron in India time", fallbacks.get(j));
        }
        if (!thisMachine) {
            log.warn("Scheduled jobs are stopped on this machine (app.schedulers.enabled=false)");
        }
    }

    public ZoneId zone() {
        return zone;
    }

    /** When this job runs: its active row if that is a usable cron, else the properties file's. */
    public String cron(Job job) {
        String row = config.text(job.cronCode());
        if (row == null) {
            return fallbacks.get(job);
        }
        String cron = row.strip();
        if (!CronExpression.isValidExpression(cron)) {
            log.warn("Configuration {} has the unusable cron \"{}\"; using {}", job.cronCode(), row, fallbacks.get(job));
            return fallbacks.get(job);
        }
        return cron;
    }

    /** May this job run now? Says so in the log when the answer changes. */
    public boolean allowed(Job job) {
        boolean now = on(job);
        Boolean before = lastSaid.put(job, now);
        if (before != null && before != now) {
            log.info("Scheduled job {} {}", job, now ? "started" : "stopped");
        } else if (before == null && !now) {
            log.info("Scheduled job {} is stopped", job);
        }
        return now;
    }

    private boolean on(Job job) {
        return allOn() && config.bool(job.code(), true);
    }

    private boolean allOn() {
        return thisMachine && config.bool(ALL, true);
    }

    @Override
    public boolean reads(String code) {
        return ALL.equals(code) || onJob(code) != null || cronJob(code) != null;
    }

    @Override
    public String inEffect(String code) {
        Job cron = cronJob(code);
        if (cron != null) {
            return cron(cron);
        }
        return String.valueOf(ALL.equals(code) ? allOn() : on(onJob(code)));
    }

    @Override
    public String fallback(String code) {
        Job cron = cronJob(code);
        return cron != null ? fallbacks.get(cron) : "true";
    }

    /** A cron must be valid, come round again, and not run more than once a minute: these jobs read the database. */
    @Override
    public void check(String code, String value) {
        if (cronJob(code) == null) {
            String v = value.toLowerCase(Locale.ROOT);
            if (!v.equals("true") && !v.equals("false")) {
                throw ApiException.badRequest("This setting is true or false");
            }
            return;
        }
        if (!CronExpression.isValidExpression(value)) {
            throw ApiException.badRequest("That is not a valid cron. It has 6 parts: second minute hour day month weekday, such as 0 0 8 * * * for 8:00 every day");
        }
        CronExpression cron = CronExpression.parse(value);
        LocalDateTime first = cron.next(LocalDateTime.now(zone));
        LocalDateTime second = first == null ? null : cron.next(first);
        if (first == null) {
            throw ApiException.badRequest("That cron never comes round");
        }
        if (second != null && Duration.between(first, second).compareTo(SHORTEST_GAP) < 0) {
            throw ApiException.badRequest("A job can run at most once a minute");
        }
    }

    @Override
    public String whyDifferent(String code) {
        if (cronJob(code) != null) {
            return "from the properties file";
        }
        if (!thisMachine) {
            return "stopped on this server by its properties (SCHEDULERS_ENABLED)";
        }
        if (!ALL.equals(code) && !config.bool(ALL, true)) {
            return "stopped by " + ALL;
        }
        return "no active row, so it runs";
    }

    @Override
    public List<String> choices(String code) {
        return cronJob(code) != null ? null : List.of("true", "false");
    }

    /** For a cron row: when it runs next, or that it is stopped. */
    @Override
    public String detail(String code) {
        Job job = cronJob(code);
        if (job == null) {
            return null;
        }
        if (!on(job)) {
            return "Stopped, so it does not run";
        }
        ZonedDateTime next = CronExpression.parse(cron(job)).next(ZonedDateTime.now(zone));
        return next == null ? "Never comes round" : "Next run: " + NEXT.format(next);
    }

    @Override
    public String hint(String code) {
        return cronJob(code) == null ? null
                : "6 parts in India time: second minute hour day month weekday. 0 0 8 * * * = 8:00 every day. 0 */5 * * * * = every 5 minutes. 0 30 9 * * MON = Mondays at 9:30.";
    }

    private static Job onJob(String code) {
        return Arrays.stream(Job.values()).filter(j -> j.code().equals(code)).findFirst().orElse(null);
    }

    private static Job cronJob(String code) {
        return Arrays.stream(Job.values()).filter(j -> j.cronCode().equals(code)).findFirst().orElse(null);
    }
}
