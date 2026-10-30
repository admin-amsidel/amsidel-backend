/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.time.Instant;

/** What happened to one personal mail (not stored; the portal shows it on screen). */
public record MailResult(Long employeeId, String name, String email, boolean sent, String error, Instant at) {

    public static MailResult sent(Long employeeId, String name, String email) {
        return new MailResult(employeeId, name, email, true, null, Instant.now());
    }

    public static MailResult failed(Long employeeId, String name, String email, String error) {
        return new MailResult(employeeId, name, email, false, error, Instant.now());
    }
}
