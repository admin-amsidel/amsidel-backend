/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.StoredFile;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.StoredFileRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * The register of stored files: everything that saves or removes a file tells it here, inside the same transaction, so
 * a failed upload leaves no line behind. StoredFileSync catches anything removed another way.
 */
@Component
@RequiredArgsConstructor
public class StoredFiles {

    private final StoredFileRepository repository;
    private final EmployeeRepository employees;

    /** A file was saved, or replaced the one of that row. */
    public void record(StoredFileCategory category, Long refId, Brand brand, String originalName, String fallbackName, String owner, String contentType,
                       long sizeBytes, StoredFile.Location location) {
        StoredFile f = repository.findByCategoryAndRefId(category, refId).orElseGet(StoredFile::new);
        f.setCategory(category);
        f.setRefId(refId);
        f.setBrand(brand);
        f.setName(cleanName(originalName, fallbackName));
        f.setOwner(limit(owner, 300));
        f.setContentType(limit(contentType, 100));
        f.setFileType(fileType(contentType));
        f.setSizeBytes(sizeBytes);
        f.setLocation(location);
        f.setUploadedBy(uploader());
        f.setUploadedAt(Instant.now());
        repository.save(f);
    }

    public void forget(StoredFileCategory category, Long refId) {
        repository.forget(category, refId);
    }

    /** JPG, PNG, WEBP, PDF, EXCEL, WORD or OTHER. */
    public static String fileType(String contentType) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (t.contains("jpeg") || t.contains("jpg")) {
            return "JPG";
        }
        if (t.contains("png")) {
            return "PNG";
        }
        if (t.contains("webp")) {
            return "WEBP";
        }
        if (t.contains("pdf")) {
            return "PDF";
        }
        if (t.contains("excel") || t.contains("spreadsheet")) {
            return "EXCEL";
        }
        if (t.contains("word")) {
            return "WORD";
        }
        return "OTHER";
    }

    /** The name without folders, or the fallback when there is none. */
    static String cleanName(String original, String fallback) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return limit(name.isEmpty() ? fallback : name, 200);
    }

    private Employee uploader() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser me ? employees.getReferenceById(me.id()) : null;
    }

    private static String limit(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
