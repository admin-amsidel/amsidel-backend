/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailFile;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attachments and pictures for staff mail, uploaded once and then added to every person's mail. Kept in memory
 * only, for the person who uploaded them, and dropped after a few hours (nothing is stored in the database).
 * Allowed: JPG, PNG, GIF and WebP pictures, PDF, and Word (.doc, .docx), judged by the file's first bytes.
 */
@Component
public class MailFileStore {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    /** All files in one mail: Brevo takes 20 MB a mail, and files grow by a third when mailed. */
    public static final int MAX_TOTAL = 14 * 1024 * 1024;
    private static final long MAX_KEPT = 120L * 1024 * 1024;
    private static final Duration KEEP = Duration.ofHours(4);

    /** One uploaded file; width is the picture's width in pixels (0 = not a picture). */
    public record Stored(String id, String owner, String name, String contentType, byte[] bytes, int width, Instant at) {
        /** Shown inside the message: mail apps show JPG, PNG and GIF reliably, WebP not everywhere. */
        public boolean inlineable() {
            return width > 0 && !contentType.equals("image/webp");
        }

        MailFile view() {
            return new MailFile(id, name, bytes.length, contentType, width > 0, inlineable());
        }
    }

    private final Map<String, Stored> files = new ConcurrentHashMap<>();

    public MailFile put(AuthUser me, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to attach");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("Each file must be 10 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read; please try again");
        }
        String name = cleanName(file.getOriginalFilename());
        String type = detectType(bytes, name);
        if (type == null) {
            throw ApiException.badRequest("Attach pictures (JPG, PNG, GIF, WebP), PDF or Word (.doc, .docx) files only");
        }
        dropOld();
        long kept = files.values().stream().mapToLong(s -> s.bytes().length).sum();
        if (kept + bytes.length > MAX_KEPT) {
            throw ApiException.conflict("The server is holding too many attachments right now; try again in a while");
        }
        Stored s = new Stored(UUID.randomUUID().toString(), me.userId(), name, type, bytes,
                type.startsWith("image/") ? width(bytes) : 0, Instant.now());
        files.put(s.id(), s);
        return s.view();
    }

    /** A file this person uploaded; a clear error when it has expired or belongs to someone else. */
    public Stored get(AuthUser me, String id) {
        Stored s = id == null ? null : files.get(id);
        if (s == null || !s.owner().equals(me.userId())) {
            throw ApiException.badRequest("An attached file is no longer on the server; remove it and attach it again");
        }
        return s;
    }

    public void remove(AuthUser me, String id) {
        files.computeIfPresent(id, (k, s) -> s.owner().equals(me.userId()) ? null : s);
    }

    private void dropOld() {
        Instant limit = Instant.now().minus(KEEP);
        files.values().removeIf(s -> s.at().isBefore(limit));
    }

    /** Judged by the first bytes; Word files also by their extension (a .docx is a zip inside). */
    static String detectType(byte[] b, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        if (b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4 && lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11
                && (b[3] & 0xFF) == 0xE0 && lower.endsWith(".doc")) {
            return "application/msword";
        }
        return null;
    }

    /** The picture's width without decoding it; 1 when it cannot be read (still a picture). */
    private static int width(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return 1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return Math.max(1, reader.getWidth(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return 1;
        }
    }

    /** Just the name (no folders), without characters mail apps trip over. */
    private static String cleanName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        if (name.isEmpty()) {
            name = "attachment";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
