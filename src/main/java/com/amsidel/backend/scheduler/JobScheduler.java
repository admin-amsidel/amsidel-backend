/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.scheduler;

import com.amsidel.backend.service.ChatSignals;
import com.amsidel.backend.service.ConfigValues;
import com.amsidel.backend.service.ConfigurationService;
import com.amsidel.backend.service.SchedulerSwitch;
import com.amsidel.backend.service.SchedulerSwitch.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Sets the timer of each scheduled job from its cron in the configurations table, and sets it again when that changes, with
 * no restart: at once from the Configuration page, and within about two minutes from a change straight in the database or
 * from another server. Stopping a job is separate: its timer stays set and the job does nothing (SchedulerSwitch).
 */
@Slf4j
@Component
public class JobScheduler {

    private record Armed(String cron, ScheduledFuture<?> timer) {
    }

    private final TaskScheduler scheduler;
    private final SchedulerSwitch schedulers;
    private final ConfigValues config;
    private final boolean autoStart;
    private final Map<Job, Runnable> work = new EnumMap<>(Job.class);
    private final Map<Job, Armed> armed = new EnumMap<>(Job.class);

    public JobScheduler(TaskScheduler scheduler, SchedulerSwitch schedulers, ConfigValues config, ProductCountScheduler productCount, SessionExpiryScheduler sessionCheck,
                        LeaveExpiryNotifier leaveExpiry, ChatCleanup chatCleanup, ChatSignals chatSignals,
                        com.amsidel.backend.service.StorageService storage, @Value("${app.schedulers.auto-start:true}") boolean autoStart) {
        this.scheduler = scheduler;
        this.schedulers = schedulers;
        this.config = config;
        this.autoStart = autoStart;
        work.put(Job.PRODUCT_COUNT, productCount::logProductCounts);
        work.put(Job.SESSION_CHECK, sessionCheck::tick);
        work.put(Job.LEAVE_EXPIRY, leaveExpiry::run);
        work.put(Job.CHAT_CLEANUP, chatCleanup::clean);
        work.put(Job.CHAT_REPORT, chatSignals::report);
        work.put(Job.STORAGE, storage::scheduledSnapshot);
    }

    /** After the rows are made, so that they are what is read. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void start() {
        refresh();
    }

    /** Someone saved a change on the Configuration page. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(ConfigurationService.Changed event) {
        config.reload();
        refresh();
    }

    /** A change made another way: straight in the database, or on another server. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void watch() {
        refresh();
    }

    /** Sets again only the timers whose cron has changed, so a job about to run is not disturbed by a change to another. */
    public synchronized void refresh() {
        if (!autoStart) {
            return;
        }
        for (Map.Entry<Job, Runnable> e : work.entrySet()) {
            Job job = e.getKey();
            String cron = schedulers.cron(job);
            Armed now = armed.get(job);
            if (now != null && now.cron().equals(cron)) {
                continue;
            }
            if (now != null) {
                // a run already under way finishes
                now.timer().cancel(false);
                log.info("Scheduled job {} now runs at \"{}\" (was \"{}\")", job, cron, now.cron());
            } else {
                log.info("Scheduled job {} runs at \"{}\"", job, cron);
            }
            armed.put(job, new Armed(cron, scheduler.schedule(guarded(job, e.getValue()), new CronTrigger(cron, schedulers.zone()))));
        }
    }

    /** One failed run does not end the job's timer: the next turn tries again. */
    private static Runnable guarded(Job job, Runnable run) {
        return () -> {
            try {
                run.run();
            } catch (RuntimeException ex) {
                log.warn("Scheduled job {} failed: {}", job, ex.getMessage());
            }
        };
    }
}
