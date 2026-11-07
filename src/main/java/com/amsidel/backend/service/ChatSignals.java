/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What keeps the chat window's polling cheap, all in memory (one API instance; moves to Redis with several):
 * <ul>
 *   <li>a change counter per person, raised when a message is sent or read, or a chat they are in changes, so the
 *   minimised window's check (/changes) tells whether a full /sync is worth it without touching the database;</li>
 *   <li>at most about one /sync a second per person (429 above it), against a faulty client or a script;</li>
 *   <li>a count of chat requests per person, logged every hour to see what the chat costs.</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatSignals {

    /** /sync closer together than this from one person is refused (app.chat.min-sync-gap-ms; 0 = no limit). */
    private final Duration minGap;
    private final SchedulerSwitch schedulers;

    /** Raised for everyone at once (e.g. the daily clean-up), and on restart the versions start again. */
    private final AtomicLong epoch = new AtomicLong(System.currentTimeMillis());
    private final Map<Long, AtomicLong> versions = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSync = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> syncs = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> checks = new ConcurrentHashMap<>();

    public ChatSignals(SchedulerSwitch schedulers, @org.springframework.beans.factory.annotation.Value("${app.chat.min-sync-gap-ms:900}") long minGapMs) {
        this.schedulers = schedulers;
        this.minGap = Duration.ofMillis(Math.max(0, minGapMs));
    }

    /** This person's version: it changes whenever something in their chats does. */
    public String version(Long employeeId) {
        return epoch.get() + "." + versions.computeIfAbsent(employeeId, k -> new AtomicLong()).get();
    }

    /** Something changed for these people (a message, a read tick, a member). */
    public void changed(Collection<Long> employeeIds) {
        employeeIds.forEach(id -> versions.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet());
    }

    /** Something changed for everyone (messages cleared). */
    public void changedForAll() {
        epoch.incrementAndGet();
    }

    /** A /sync: refused when the last one from this person was under a second ago. */
    public void syncing(Long employeeId) {
        Instant now = Instant.now();
        Instant before = lastSync.put(employeeId, now);
        if (before != null && !minGap.isZero() && before.isAfter(now.minus(minGap))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many chat checks; slow down");
        }
        syncs.computeIfAbsent(employeeId, k -> new AtomicLong()).incrementAndGet();
    }

    /** A /changes check. */
    public void checking(Long employeeId) {
        checks.computeIfAbsent(employeeId, k -> new AtomicLong()).incrementAndGet();
    }

    /** Every hour: how many chat checks came in, so the saving and each customer's cost can be seen. */
    public void report() {
        if (!schedulers.allowed(SchedulerSwitch.Job.CHAT_REPORT)) {
            return;
        }
        java.util.Set<Long> people = new java.util.HashSet<>();
        syncs.forEach((id, n) -> { if (n.get() > 0) people.add(id); });
        checks.forEach((id, n) -> { if (n.get() > 0) people.add(id); });
        long s = drain(syncs);
        long c = drain(checks);
        if (s + c > 0) {
            log.info("Team chat, last hour: {} sync(s) and {} change check(s) from {} people ({} a person)", s, c, people.size(),
                    (s + c) / people.size());
        }
    }

    private static long drain(Map<Long, AtomicLong> counts) {
        long total = 0;
        for (AtomicLong n : counts.values()) {
            total += n.getAndSet(0);
        }
        return total;
    }
}
