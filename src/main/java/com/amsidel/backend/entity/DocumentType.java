/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * The documents an employee keeps on file, one of each: a PDF or a photo / scan (JPEG, PNG), except the passport
 * size photo, which must be an image.
 */
public enum DocumentType {
    PAN_CARD("PAN card", false),
    AADHAAR_CARD("Aadhaar card", false),
    BANK_PROOF("Bank statement / passbook", false),
    OFFER_LETTER("Offer letter", false),
    PASSPORT_PHOTO("Passport size photo", true);

    private final String label;
    private final boolean imageOnly;

    DocumentType(String label, boolean imageOnly) {
        this.label = label;
        this.imageOnly = imageOnly;
    }

    public String label() {
        return label;
    }

    public boolean imageOnly() {
        return imageOnly;
    }
}
