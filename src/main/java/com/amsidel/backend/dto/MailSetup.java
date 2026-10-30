/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

/**
 * Whether the server can send mail, the address mail comes from, and the office address copied (CC) on every mail
 * (empty when none is set).
 */
public record MailSetup(boolean ready, String from, String fromName, String alwaysCc) {
}
