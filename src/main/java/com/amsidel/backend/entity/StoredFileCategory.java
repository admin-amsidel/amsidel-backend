/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What a stored file is for. ref in {@link StoredFile} says which row of that kind it belongs to. */
@Getter
@RequiredArgsConstructor
public enum StoredFileCategory {
    EMPLOYEE_DOCUMENT("Employee documents"),
    PROFILE_PHOTO("Profile photos"),
    AGENT_PHOTO("Agent photos"),
    CHECK_IN_PHOTO("Check-in photos"),
    PROJECT_PHOTO("Project status photos"),
    NEWS_PICTURE("News pictures"),
    BRAND_LOGO("Brand logos"),
    UPI_QR("UPI QR codes"),
    /** Deleting one deletes the issued letter itself, and needs the Delete issued offer letter tick as well. */
    OFFER_LETTER("Offer letters");

    private final String label;
}
