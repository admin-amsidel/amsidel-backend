/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AppConfiguration;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.AppConfigurationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Adding, changing and deleting rows of the configurations table (Administration > Configuration). A value the portal reads
 * is checked before it is saved, and the change applies at once. Each change is written to the server log with who made it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationService {

    /**
     * usedByPortal: the portal reads this code. inEffect and fallback: the value it uses now, and with no active row; inEffectNote:
     * why that differs from this row; choices: the only values it takes. Null for a code it does not read.
     */
    public record View(String code, String description, String value, boolean active, boolean usedByPortal, String inEffect, String fallback, String inEffectNote,
                       List<String> choices, String detail, String hint, String updatedByName, Instant updatedAt) {
    }

    /** A row was added, changed or deleted: said to JobScheduler once it is saved. */
    public record Changed(String code) {
    }

    public record CreateBody(
            @NotBlank(message = "Give the code") @Size(max = 60, message = "The code can be at most 60 characters")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "The code may use letters, numbers and _, and starts with a letter") String code,
            @Size(max = 300, message = "The description can be at most 300 characters") String description,
            @NotBlank(message = "Give the value") @Size(max = 500, message = "The value can be at most 500 characters") String value,
            Boolean active) {
    }

    public record UpdateBody(
            @Size(max = 300, message = "The description can be at most 300 characters") String description,
            @NotBlank(message = "Give the value") @Size(max = 500, message = "The value can be at most 500 characters") String value,
            Boolean active) {
    }

    private final AppConfigurationRepository repository;
    private final EmployeeRepository employees;
    private final ConfigValues config;
    private final List<KnownSettings> known;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<View> list() {
        return repository.findAll().stream().sorted(Comparator.comparing(AppConfiguration::getCode)).map(this::view).toList();
    }

    @Transactional
    public View create(AuthUser me, CreateBody body) {
        String code = body.code().strip().toUpperCase(Locale.ROOT);
        if (repository.existsById(code)) {
            throw ApiException.conflict("The code " + code + " is already there");
        }
        AppConfiguration c = new AppConfiguration();
        c.setCode(code);
        apply(me, c, body.description(), body.value(), body.active());
        repository.saveAndFlush(c);
        config.reload();
        events.publishEvent(new Changed(code));
        log.info("Configuration {} added by employee {}: value \"{}\", active {}", code, me.id(), c.getValue(), c.isActive());
        return view(c);
    }

    @Transactional
    public View update(AuthUser me, String code, UpdateBody body) {
        AppConfiguration c = find(code);
        String before = c.getValue();
        boolean wasActive = c.isActive();
        apply(me, c, body.description(), body.value(), body.active());
        repository.saveAndFlush(c);
        config.reload();
        events.publishEvent(new Changed(c.getCode()));
        log.info("Configuration {} changed by employee {}: value \"{}\" to \"{}\", active {} to {}", c.getCode(), me.id(), before, c.getValue(), wasActive, c.isActive());
        return view(c);
    }

    @Transactional
    public void delete(AuthUser me, String code) {
        AppConfiguration c = find(code);
        repository.delete(c);
        repository.flush();
        config.reload();
        events.publishEvent(new Changed(c.getCode()));
        log.info("Configuration {} deleted by employee {}: its value was \"{}\"", c.getCode(), me.id(), c.getValue());
    }

    private AppConfiguration find(String code) {
        return repository.findById(code.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Configuration not found: " + code));
    }

    private void apply(AuthUser me, AppConfiguration c, String description, String value, Boolean active) {
        String clean = value.strip();
        known(c.getCode()).ifPresent(k -> k.check(c.getCode(), clean));
        c.setDescription(StringUtils.hasText(description) ? description.strip() : null);
        c.setValue(clean);
        c.setActive(active == null || active);
        c.setUpdatedByName(employees.findById(me.id()).map(e -> e.getName()).orElse(me.userId()));
        c.setUpdatedAt(Instant.now());
    }

    private java.util.Optional<KnownSettings> known(String code) {
        return known.stream().filter(k -> k.reads(code)).findFirst();
    }

    private View view(AppConfiguration c) {
        KnownSettings k = known(c.getCode()).orElse(null);
        String code = c.getCode();
        String inEffect = k == null ? null : k.inEffect(code);
        boolean differs = k != null && (!c.isActive() || !c.getValue().equalsIgnoreCase(inEffect));
        return new View(code, c.getDescription(), c.getValue(), c.isActive(), k != null, inEffect, k == null ? null : k.fallback(code), differs ? k.whyDifferent(code) : null,
                k == null ? null : k.choices(code), k == null ? null : k.detail(code), k == null ? null : k.hint(code), c.getUpdatedByName(), c.getUpdatedAt());
    }
}
