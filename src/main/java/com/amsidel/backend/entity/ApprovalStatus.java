/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * Where an attendance entry or a work log entry stands with the person's approver. Only approved time counts. Rows from
 * before approvals have no status at all, and count as approved.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
