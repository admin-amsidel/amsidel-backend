/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A project status (Queued, Quotation issued, ... Completed, Cancelled), kept in a table: a project and each of its
 * status updates hold the code. The phase says what it means for the work: still open, done, or called off.
 */
@Entity
@Table(name = "project_statuses")
@Getter
@Setter
@NoArgsConstructor
public class ProjectStage {

    public static final String QUEUED = "QUEUED";
    public static final String STARTED = "IN_PROGRESS";
    /** Set when a sales order of the project is confirmed. */
    public static final String CONFIRMED = "CONFIRMED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    /** What a status means for the work. */
    public enum Phase {
        OPEN,
        DONE,
        CANCELLED
    }

    @Id
    @Column(length = 30)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Phase phase;

    @Column(nullable = false)
    private int sortOrder;

    /** Inactive: kept for the projects that have it, not offered for new changes. */
    @Column(nullable = false)
    private boolean active = true;

    private Instant updatedAt;

    public boolean isOpen() {
        return phase == Phase.OPEN;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }
}
