/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AppSetting;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.AppSettingRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may send staff mail how, and to how many (the ticks under Staff mail in Menu access):
 * Send by email (the server sends it; WhatsApp opens in the sender's own WhatsApp, so that tick is the page's),
 * Add picture and Attach files (files on the mail), All employees, and Choose people, which reaches at most the pick limit (5 unless an owner or group admin changes
 * it) in one send. Without All employees, the limit holds on the server too: at most that many different people
 * within a few minutes (sending the failed ones again is the same people, so it is not held back).
 */
@Service
@RequiredArgsConstructor
public class StaffMailRules {

    static final String PICK_LIMIT = "mail.pick-limit";
    static final int DEFAULT_PICK_LIMIT = 5;
    public static final int MAX_PICK_LIMIT = 500;
    /** How long one send counts as one: plenty for a handful of mails, short enough not to get in the way. */
    static final Duration ONE_SEND = Duration.ofMinutes(5);

    private final AppSettingRepository settings;
    private final MenuAccessService menuAccess;
    /** Per sender: who they mailed lately and when. */
    private final Map<Long, Map<Long, Instant>> recent = new ConcurrentHashMap<>();

    public record Rules(int pickLimit, boolean canChangeLimit) {
    }

    @Transactional(readOnly = true)
    public Rules rules(AuthUser me) {
        return new Rules(pickLimit(), canChangeLimit(me));
    }

    @Transactional(readOnly = true)
    public int pickLimit() {
        return settings.findById(PICK_LIMIT).map(s -> parse(s.getValue())).orElse(DEFAULT_PICK_LIMIT);
    }

    /** Owners and group admins set the limit. */
    @Transactional
    public Rules setPickLimit(AuthUser me, int limit) {
        if (!canChangeLimit(me)) {
            throw ApiException.forbidden("Only owners and group admins can change the Choose people limit");
        }
        if (limit < 1 || limit > MAX_PICK_LIMIT) {
            throw ApiException.badRequest("The limit must be between 1 and " + MAX_PICK_LIMIT);
        }
        AppSetting s = settings.findById(PICK_LIMIT).orElseGet(() -> new AppSetting(PICK_LIMIT, ""));
        s.setValue(String.valueOf(limit));
        settings.save(s);
        return new Rules(limit, true);
    }

    /** Uploading a file for a staff mail needs Add picture or Attach files. */
    public void checkUpload(AuthUser me) {
        if (!menuAccess.can(me, Menus.MAIL_PICTURE, Menus.MAIL_ATTACH)) {
            throw ApiException.forbidden("You may not add pictures or files to staff mail. Ask your admin for \"Add picture\" or \"Attach files\" under Staff mail.");
        }
    }

    /**
     * Before one email goes to this employee: the Send by email tick, Add picture / Attach files when the mail has
     * them, and the limit when it applies.
     */
    public void checkEmail(AuthUser me, Long employeeId, boolean pictures, boolean attachments) {
        if (!menuAccess.can(me, Menus.MAIL_EMAIL)) {
            throw ApiException.forbidden("You may not send staff mail by email. Ask your admin for \"Send by email\" under Staff mail.");
        }
        if (pictures && !menuAccess.can(me, Menus.MAIL_PICTURE)) {
            throw ApiException.forbidden("You may not put pictures in staff mail. Ask your admin for \"Add picture\" under Staff mail.");
        }
        if (attachments && !menuAccess.can(me, Menus.MAIL_ATTACH)) {
            throw ApiException.forbidden("You may not attach files to staff mail. Ask your admin for \"Attach files\" under Staff mail.");
        }
        if (menuAccess.can(me, Menus.MAIL_ALL)) {
            return;
        }
        if (!menuAccess.can(me, Menus.MAIL_PICK)) {
            throw ApiException.forbidden("You may not send staff mail to anyone. Ask your admin for \"Choose people\" under Staff mail.");
        }
        int limit = pickLimit();
        Instant now = Instant.now();
        Map<Long, Instant> mine = recent.computeIfAbsent(me.id(), k -> new ConcurrentHashMap<>());
        synchronized (mine) {
            mine.values().removeIf(at -> at.isBefore(now.minus(ONE_SEND)));
            if (!mine.containsKey(employeeId) && mine.size() >= limit) {
                throw ApiException.conflict("You can send to at most " + limit + (limit == 1 ? " person" : " people")
                        + " at a time. Wait a few minutes before the next send.");
            }
            mine.put(employeeId, now);
        }
    }

    private static boolean canChangeLimit(AuthUser me) {
        return me.isOwner() || me.isGroupAdmin();
    }

    private static int parse(String value) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_PICK_LIMIT;
        }
    }
}
