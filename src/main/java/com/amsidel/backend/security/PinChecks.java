/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.security;

import com.amsidel.backend.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Checks a PIN at sign-in, with only a few checks running at once. A check is slow on purpose and uses a whole CPU core,
 * so running more than the server has cores only slows all of them, and every other page with them. The rest wait their
 * turn in order; past the wait limit they are told to try again rather than left hanging. Call this with no database
 * connection held: a check takes far longer than a query, and the pool is small.
 */
@Component
public class PinChecks {

    static final String BUSY = "Many people are signing in right now. Please try again in a moment.";

    private final PasswordEncoder passwordEncoder;
    private final Semaphore turns;
    private final Duration maxWait;

    public PinChecks(PasswordEncoder passwordEncoder,
                     @Value("${app.login.max-concurrent-checks:0}") int maxConcurrent,
                     @Value("${app.login.check-wait:PT15S}") Duration maxWait) {
        this.passwordEncoder = passwordEncoder;
        // 0 = follow the machine, so more CPU on the server needs no change here; at least 2, as a small cloud server reports 1
        this.turns = new Semaphore(maxConcurrent > 0 ? maxConcurrent : Math.max(2, Runtime.getRuntime().availableProcessors()), true);
        this.maxWait = maxWait;
    }

    /** Whether this PIN matches the stored hash; 503 when it could not get a turn in time. */
    public boolean matches(String pin, String hash) {
        try {
            if (!turns.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, BUSY);
        }
        try {
            return passwordEncoder.matches(pin, hash);
        } finally {
            turns.release();
        }
    }
}
