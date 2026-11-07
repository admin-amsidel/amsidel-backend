/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.SignedInCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Whose sign-in is still running. Marks a sign-in past its time as expired on the employee, ends one when the person signs
 * out, and says who is signed in now: everyone whose sign-in is within the time and not ended. That counts people, not
 * devices, and someone who closed the browser without signing out counts until their time runs out.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    /** One person signed in now. timeZone: their brand's, which their times are shown in. */
    public record ActiveUser(Long id, String name, String empNumber, String brandCode, Instant signedInAt, Instant expiresAt, String timeZone) {
    }

    public record ActiveUsers(int count, int maxMinutes, Instant at, List<ActiveUser> people) {
    }

    private final EmployeeRepository employees;
    private final SessionSettings settings;
    private final SignedInCache cache;
    private final TimeZones timeZones;

    /** Run by the scheduler. Says how many were marked. */
    @Transactional
    public int expireOverdue() {
        Instant now = Instant.now();
        int marked = employees.expireSessions(now.minus(settings.maxValidity()), now.truncatedTo(ChronoUnit.SECONDS));
        if (marked > 0) {
            // an update like this tells no one, and every call checks the cached copy
            cache.evictAll();
        }
        return marked;
    }

    /** Signing out: this ends the person's sign-in on all their devices. */
    @Transactional
    public void signOut(AuthUser me) {
        employees.endSession(me.id(), Instant.now().truncatedTo(ChronoUnit.SECONDS));
        cache.evict(me.id());
    }

    /** Just how many, for the Overview: nobody's details leave the server until someone asks to see them. */
    @Transactional(readOnly = true)
    public ActiveUsers activeCount(AuthUser me) {
        ActiveUsers all = active(me);
        return new ActiveUsers(all.count(), all.maxMinutes(), all.at(), List.of());
    }

    /** Everyone for an owner or a group-level person; their own brand's for someone in a brand. */
    @Transactional(readOnly = true)
    public ActiveUsers active(AuthUser me) {
        Duration max = settings.maxValidity();
        Instant now = Instant.now();
        List<Employee> found = employees.signedInSince(now.minus(max));
        List<ActiveUser> people = found.stream()
                .filter(e -> me.isOwner() || me.brandId() == null || (e.getBrand() != null && me.brandId().equals(e.getBrand().getId())))
                .sorted(Comparator.comparing(Employee::getLastLoginAt).reversed())
                .map(e -> new ActiveUser(e.getId(), e.getName(), e.getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getCode(),
                        e.getLastLoginAt(), e.getLastLoginAt().plus(max), timeZones.of(e.getBrand()).getId()))
                .toList();
        return new ActiveUsers(people.size(), (int) max.toMinutes(), now, people);
    }
}
