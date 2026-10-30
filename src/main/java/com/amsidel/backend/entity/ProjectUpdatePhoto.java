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

/** A photo of the work in a project status update (JPEG, PNG or WebP, shrunk in the browser before upload). */
@Entity
@Table(name = "project_update_photos")
@Getter
@Setter
@NoArgsConstructor
public class ProjectUpdatePhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "update_id", nullable = false)
    private ProjectUpdate update;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private Instant createdAt;
}
