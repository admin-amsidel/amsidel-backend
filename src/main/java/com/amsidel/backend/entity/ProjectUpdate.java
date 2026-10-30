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
import java.util.ArrayList;
import java.util.List;

/**
 * A status update of a project for its customer: the stage (a project status), a title, what was done, when, and
 * photos of the work. Shown in the portal and on the customer's status link; who posted and changed it is kept.
 */
@Entity
@Table(name = "project_updates", indexes = @Index(name = "ix_project_updates_project", columnList = "project_id, happened_at"))
@Getter
@Setter
@NoArgsConstructor
public class ProjectUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The stage: a project status code (project_statuses). */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 2000)
    private String description;

    /** When the work happened (the date and time shown); now by default. */
    @Column(name = "happened_at", nullable = false)
    private Instant happenedAt;

    @OneToMany(mappedBy = "update", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private List<ProjectUpdatePhoto> photos = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    private Instant updatedAt;
}
