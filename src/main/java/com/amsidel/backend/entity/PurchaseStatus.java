/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/** A purchase bill: being written, received into stock, or cancelled. */
public enum PurchaseStatus {
    DRAFT,
    RECEIVED,
    CANCELLED
}
