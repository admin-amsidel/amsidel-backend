/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * Who a news item is for. Staff see every one on the dashboard. Employees: staff only. Customers: for staff to pass
 * on to their customers (shared on WhatsApp), not on the website. Public and All: also on the website (a discount
 * shows under its Offers).
 */
public enum NewsAudience {
    EMPLOYEES,
    CUSTOMERS,
    PUBLIC,
    EVERYONE;

    /** Shown on the public website. */
    public boolean onWebsite() {
        return this == PUBLIC || this == EVERYONE;
    }
}
