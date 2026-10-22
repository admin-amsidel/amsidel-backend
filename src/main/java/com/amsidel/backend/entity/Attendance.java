package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One attendance entry: a check-in (server time, live camera photo, work mode, location, device) and its
 * check-out ("End work today", or closing a field visit). Office and work-from-home days have one entry; a field
 * day can have several, one per visit, numbered 1, 2, 3 within the day (Asia/Kolkata calendar day).
 * Coordinates are null when the person did not allow location; the flags record what the server noticed.
 */
@Entity
@Table(name = "attendance",
        uniqueConstraints = @UniqueConstraint(name = "uk_attendance_entry", columnNames = {"employee_id", "work_date", "entry_no"}),
        indexes = @Index(name = "ix_attendance_day", columnList = "work_date"))
@Getter
@Setter
@NoArgsConstructor
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 1 for the day's first entry, then 2, 3… for more field visits (the default lets older rows get the column). */
    @Column(name = "entry_no", nullable = false, columnDefinition = "integer default 1 not null")
    private int entryNo = 1;

    /** Field visits: the place or client visited. */
    @Column(name = "place", length = 120)
    private String place;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 10)
    private WorkMode workMode;

    // ---- check-in ----

    /** Set by the server when the check-in arrives; the device clock is never used. */
    @Column(name = "check_in_at", nullable = false)
    private Instant checkInAt;

    @Column(name = "check_in_latitude")
    private Double checkInLatitude;

    @Column(name = "check_in_longitude")
    private Double checkInLongitude;

    /** Metres, as reported by the device. */
    @Column(name = "check_in_accuracy")
    private Double checkInAccuracy;

    @Column(name = "check_in_location_missing", nullable = false)
    private boolean checkInLocationMissing;

    @Column(name = "check_in_low_accuracy", nullable = false)
    private boolean checkInLowAccuracy;

    @Column(name = "check_in_ip", length = 64)
    private String checkInIp;

    /** Under the attendance photo folder, e.g. attendance/2026/10/42/22.jpg. */
    @Column(name = "photo_path", nullable = false, length = 200)
    private String photoPath;

    @Column(name = "photo_bytes", nullable = false)
    private int photoBytes;

    // ---- device ----

    /** e.g. web, android, ios. */
    @Column(name = "device_platform", length = 40)
    private String devicePlatform;

    @Column(name = "device_os", length = 60)
    private String deviceOs;

    @Column(name = "app_version", length = 40)
    private String appVersion;

    // ---- check-out ----

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column(name = "check_out_latitude")
    private Double checkOutLatitude;

    @Column(name = "check_out_longitude")
    private Double checkOutLongitude;

    @Column(name = "check_out_accuracy")
    private Double checkOutAccuracy;

    @Column(name = "check_out_location_missing", nullable = false)
    private boolean checkOutLocationMissing;

    @Column(name = "check_out_low_accuracy", nullable = false)
    private boolean checkOutLowAccuracy;

    @Column(name = "check_out_ip", length = 64)
    private String checkOutIp;

    /** Written when closing: how the meeting or the day went. */
    @Column(name = "check_out_note", length = 500)
    private String checkOutNote;
}
