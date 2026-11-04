/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


/**
 * Who may send staff mail how, and to how many (the ticks under Email in Menu access):
 * Send by email (the server sends it; WhatsApp opens in the sender's own WhatsApp, so that tick is the page's),
 * Add picture and Attach files (files on the mail), All employees, and Choose people, which reaches at most the limit
 * of each brand (StaffMailConfig) in one send. Without All employees, that limit holds on the server too: at most that
 * many different people of a brand within a few minutes (sending the failed ones again is the same people, so it is
 * not held back).
 */
@Service
@RequiredArgsConstructor
public class StaffMailRules {

    private final StaffMailConfig config;
    private final MenuAccessService menuAccess;

    /** Uploading a file for a staff mail needs Add picture or Attach files. */
    public void checkUpload(AuthUser me) {
        if (!menuAccess.can(me, Menus.MAIL_PICTURE, Menus.MAIL_ATTACH)) {
            throw ApiException.forbidden("You may not add pictures or files to staff mail. Ask your admin for \"Add picture\" or \"Attach files\" under Email.");
        }
    }

    /**
     * Before one email goes to this employee: the Send by email tick, Add picture / Attach files when the mail has
     * them, and the limit when it applies.
     */
    public void checkEmail(AuthUser me, Long employeeId, long brandKey, boolean pictures, boolean attachments) {
        if (!menuAccess.can(me, Menus.MAIL_EMAIL)) {
            throw ApiException.forbidden("You may not send staff mail by email. Ask your admin for \"Send by email\" under Email.");
        }
        if (pictures && !menuAccess.can(me, Menus.MAIL_PICTURE)) {
            throw ApiException.forbidden("You may not put pictures in staff mail. Ask your admin for \"Add picture\" under Email.");
        }
        if (attachments && !menuAccess.can(me, Menus.MAIL_ATTACH)) {
            throw ApiException.forbidden("You may not attach files to staff mail. Ask your admin for \"Attach files\" under Email.");
        }
        if (menuAccess.can(me, Menus.MAIL_ALL)) {
            return;
        }
        if (!menuAccess.can(me, Menus.MAIL_PICK)) {
            throw ApiException.forbidden("You may not send staff mail to anyone. Ask your admin for \"Choose people\" under Email.");
        }
        config.checkPick(me, String.valueOf(employeeId), brandKey);
    }
}
