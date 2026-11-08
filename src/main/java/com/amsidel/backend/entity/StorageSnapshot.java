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
 * The storage figures as measured by the scheduled job (three times a day): the database's size and its largest tables,
 * the disk, and the stored files by category, type, brand and place. The Storage page and the Overview read the latest
 * one, so opening them never measures anything.
 */
@Entity
@Table(name = "storage_snapshots", indexes = @Index(name = "ix_storage_snapshot_at", columnList = "measured_at"))
@Getter
@Setter
@NoArgsConstructor
public class StorageSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    /** Whether the database could say its size (PostgreSQL can). */
    @Column(nullable = false)
    private boolean databaseAvailable;

    @Column(nullable = false)
    private long databaseBytes;

    /** The size the database may grow to (app.database.max-size) when measured. */
    @Column(nullable = false)
    private long databaseMaxBytes;

    /** The disk that holds the files kept on disk; 0 when it cannot be read. */
    @Column(nullable = false)
    private long diskTotalBytes;

    @Column(nullable = false)
    private long diskUsableBytes;

    /** The 10 largest tables: [{name, bytes, rows}] as JSON. */
    @Column(nullable = false, columnDefinition = "text")
    private String tables;

    /**
     * The stored files grouped by category, file type, brand and place, with how many and how big, and those added in the
     * month of the measuring: [{category, fileType, brandId, brandCode, location, files, bytes, monthFiles, monthBytes}].
     */
    @Column(nullable = false, columnDefinition = "text")
    private String files;
}
