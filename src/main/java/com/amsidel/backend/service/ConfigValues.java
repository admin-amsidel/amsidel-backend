/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AppConfiguration;
import com.amsidel.backend.repository.AppConfigurationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Settings from the configurations table, each over its value in the properties file: an active row with a value wins;
 * inactive, empty, missing or unusable, the properties file's value applies. Every API call reads these, so the active rows
 * are held in memory and read again once a while, which also picks up a change made straight in the database.
 */
@Slf4j
@Component
public class ConfigValues {

    private final AppConfigurationRepository repository;
    private final Duration remember;
    private volatile Map<String, String> active = Map.of();
    private volatile Instant readAt = Instant.EPOCH;

    public ConfigValues(AppConfigurationRepository repository, @Value("${app.config.cache-ttl:PT60S}") Duration remember) {
        this.repository = repository;
        this.remember = remember;
    }

    /** A whole number of at least 1; the fallback for anything else. */
    public int positiveInt(String code, int fallback) {
        String value = values().get(code);
        if (value == null) {
            return fallback;
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // said below
        }
        log.warn("Configuration {} has the unusable value \"{}\"; using {} from the properties file", code, value, fallback);
        return fallback;
    }

    /** An active row's value, or null. */
    public String text(String code) {
        return values().get(code);
    }

    /** true or false, in any case; the fallback for anything else. */
    public boolean bool(String code, boolean fallback) {
        String value = values().get(code);
        if (value == null) {
            return fallback;
        }
        String v = value.strip();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        log.warn("Configuration {} has the unusable value \"{}\"; using {}", code, value, fallback);
        return fallback;
    }

    /** Reads the table again now rather than at the next turn. */
    public void reload() {
        readAt = Instant.EPOCH;
        values();
    }

    /** A row nothing reads any more. */
    public void removeOld(String code) {
        if (repository.existsById(code)) {
            repository.deleteById(code);
            log.info("Configuration {} removed: it is no longer read", code);
        }
        reload();
    }

    /** A row that is not there yet; one already there is never changed, as it may have been edited. */
    public void seed(String code, String description, String value) {
        if (!repository.existsById(code)) {
            AppConfiguration c = new AppConfiguration();
            c.setCode(code);
            c.setDescription(description);
            c.setValue(value);
            c.setActive(true);
            repository.save(c);
            log.info("Configuration {} added with the value {}", code, value);
        }
        reload();
    }

    private Map<String, String> values() {
        Instant now = Instant.now();
        if (readAt.plus(remember).isBefore(now)) {
            // whatever happens, no other call waits on this for the next while
            readAt = now;
            try {
                active = repository.findAll().stream().filter(c -> c.isActive() && StringUtils.hasText(c.getValue()))
                        .collect(Collectors.toUnmodifiableMap(AppConfiguration::getCode, AppConfiguration::getValue));
            } catch (RuntimeException e) {
                log.warn("Could not read the configurations table; keeping what was last read: {}", e.getMessage());
            }
        }
        return active;
    }
}
