package com.amsidel.backend.entity;

/** A purchase bill: being written, received into stock, or cancelled. */
public enum PurchaseStatus {
    DRAFT,
    RECEIVED,
    CANCELLED
}
