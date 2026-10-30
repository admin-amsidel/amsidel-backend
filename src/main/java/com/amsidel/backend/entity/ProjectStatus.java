/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/** Where a task of a project stands (the project's own status is a ProjectStage, kept in a table). */
public enum ProjectStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
