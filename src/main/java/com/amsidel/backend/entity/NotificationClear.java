/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Someone cleared a notification. Notifications themselves are not stored: each check works them out from the meetings,
 * so this mark is all that keeps a cleared one from coming back. It goes once the meeting is too old to notify about.
 */
@Entity
@Table(name = "notification_clears", uniqueConstraints = @UniqueConstraint(name = "uq_notification_clear", columnNames = {"employee_id", "meeting_id", "kind"}),
        indexes = @Index(name = "idx_notification_clear_meeting", columnList = "meeting_id"))
@Getter
@Setter
@NoArgsConstructor
public class NotificationClear {

    /** A meeting about to start or under way; a meeting that has closed. */
    public enum Kind { STARTING, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** The meeting's date, to know when this mark is no longer needed. */
    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;
}
