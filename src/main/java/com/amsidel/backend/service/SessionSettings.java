/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** How long a sign-in lasts: the configurations table, over the properties file. */
@Component
public class SessionSettings implements KnownSettings {

    public static final String MAX_MINUTES = "SESSION_MAX_MINUTES";
    /** Read before the session check had a cron. */
    private static final String OLD_CHECK_MINUTES = "SESSION_CHECK_MINUTES";
    private static final int MIN_SESSION_MINUTES = 5;
    private static final int MAX_SESSION_MINUTES = 43200;

    private final ConfigValues config;
    private final int maxMinutes;

    public SessionSettings(ConfigValues config, @Value("${app.session.max-minutes}") int maxMinutes) {
        this.config = config;
        this.maxMinutes = maxMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void seed() {
        config.seed(MAX_MINUTES, "Maximum time a sign-in lasts, in minutes", String.valueOf(maxMinutes));
        config.removeOld(OLD_CHECK_MINUTES);
    }

    public Duration maxValidity() {
        return Duration.ofMinutes(config.positiveInt(MAX_MINUTES, maxMinutes));
    }

    /** Does the portal read this code? */
    @Override
    public boolean reads(String code) {
        return MAX_MINUTES.equals(code);
    }

    /** The value in use now; null for a code the portal does not read. */
    @Override
    public String inEffect(String code) {
        return switch (code) {
            case MAX_MINUTES -> String.valueOf(maxValidity().toMinutes());
            default -> null;
        };
    }

    /** The properties file's value; null for a code the portal does not read. */
    @Override
    public String fallback(String code) {
        return switch (code) {
            case MAX_MINUTES -> String.valueOf(maxMinutes);
            default -> null;
        };
    }

    /** Refuses a value that cannot work. A very short sign-in would log everyone out, the person changing it too, with no way back in time. */
    @Override
    public void check(String code, String value) {
        if (MAX_MINUTES.equals(code)) {
            inRange(value, MIN_SESSION_MINUTES, MAX_SESSION_MINUTES, "A sign-in's length");
        }
    }

    private static void inRange(String value, int min, int max, String what) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(what + " is a whole number of minutes");
        }
        if (number < min || number > max) {
            throw ApiException.badRequest(what + " is from " + min + " to " + max + " minutes");
        }
    }
}
