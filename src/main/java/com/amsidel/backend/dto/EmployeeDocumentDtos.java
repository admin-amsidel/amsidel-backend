/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.time.Instant;
import java.util.List;

/** An employee's documents on file, and how complete their profile is. */
public final class EmployeeDocumentDtos {

    private EmployeeDocumentDtos() {
    }

    /** One document type: uploaded or not; the file's details when it is. */
    public record DocumentView(String type, String label, boolean imageOnly, boolean uploaded, String fileName,
                               String contentType, Integer sizeBytes, Instant uploadedAt, String uploadedByName) {
    }

    /** A downloaded document. */
    public record DocumentFile(byte[] bytes, String contentType, String fileName) {
    }

    /** One thing that makes the profile complete; group = Personal details, Bank &amp; tax or Documents. */
    public record ProfileItem(String key, String label, String group, boolean done) {
    }

    /** percent = done / total, rounded down (100 only when everything is there). */
    public record ProfileStatus(int percent, int done, int total, List<ProfileItem> items) {
    }
}
