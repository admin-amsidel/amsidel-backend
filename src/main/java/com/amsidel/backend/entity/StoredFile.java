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
 * One line of the register of every file the portal keeps: what it is, how large, whose, and who uploaded it. The file
 * itself stays where it belongs; this holds no bytes, so adding up sizes and listing files never reads a file.
 */
@Entity
@Table(name = "stored_files",
        uniqueConstraints = @UniqueConstraint(name = "uq_stored_file_ref", columnNames = {"category", "ref_id"}),
        indexes = {@Index(name = "ix_stored_file_brand", columnList = "brand_id"), @Index(name = "ix_stored_file_uploaded", columnList = "uploaded_at")})
@Getter
@Setter
@NoArgsConstructor
public class StoredFile {

    public enum Location { DATABASE, DISK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StoredFileCategory category;

    /** The id of what it belongs to: the employee document, the employee of a profile photo, the attendance entry... */
    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** Null: not of one brand (group news, group-level people). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(nullable = false, length = 200)
    private String name;

    /** Whose or what it is: "Riya Rao · PAN card". */
    @Column(nullable = false, length = 300)
    private String owner;

    /** JPG, PNG, WEBP, PDF, EXCEL, WORD or OTHER. */
    @Column(nullable = false, length = 10)
    private String fileType;

    @Column(length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Location location;

    /** Null: from before uploads were recorded, or not known. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private Employee uploadedBy;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;
}
