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
 * A skill in a review: listed by the person with their own level (1-5), or added by their manager; the manager's
 * level and note validate it. A skill the manager added has no level of the person's yet (0) until they give one.
 */
@Entity
@Table(name = "review_skills")
@Getter
@Setter
@NoArgsConstructor
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkillCategory category;

    /** 0.5-5 in half stars; 0 = not given yet (a skill the manager added). */
    @Column(name = "self_level", nullable = false, precision = 3, scale = 1)
    private java.math.BigDecimal selfLevel = java.math.BigDecimal.ZERO;

    /** Null: listed by the person; otherwise the manager who added it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_id")
    private Employee addedBy;

    /** 0.5-5 in half stars, set when the manager validates it. */
    @Column(name = "manager_level", precision = 3, scale = 1)
    private java.math.BigDecimal managerLevel;

    @Column(name = "manager_note", length = 500)
    private String managerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_id")
    private Employee validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;
}
