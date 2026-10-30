/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

/**
 * A file uploaded for staff mail, kept for a few hours on the server. image: a picture; inline: it can also be shown
 * inside the message (JPG, PNG, GIF).
 */
public record MailFile(String id, String name, long size, String contentType, boolean image, boolean inline) {
}
