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
