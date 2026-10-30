/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/** Quotations: DRAFT, SENT, ACCEPTED, REJECTED. Invoices: DRAFT, ISSUED, CANCELLED. */
public enum SalesDocStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
    ISSUED,
    CANCELLED
}
