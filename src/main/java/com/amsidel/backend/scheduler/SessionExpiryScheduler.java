/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.scheduler;

import com.amsidel.backend.service.SchedulerSwitch;
import com.amsidel.backend.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Marks sign-ins past their time as expired on the employee. JobScheduler sets when, from the configurations table. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionExpiryScheduler {

    private final SessionService sessions;
    private final SchedulerSwitch schedulers;

    public void tick() {
        if (!schedulers.allowed(SchedulerSwitch.Job.SESSION_CHECK)) {
            return;
        }
        try {
            int marked = sessions.expireOverdue();
            if (marked > 0) {
                log.info("Session check: {} expired", marked);
            }
        } catch (RuntimeException e) {
            // keep going: the next run tries again, and every call checks the time on its own anyway
            log.warn("Session check failed: {}", e.getMessage());
        }
    }
}
