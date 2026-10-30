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

/** One person's review in one cycle: their self intro; their skills and goals hang off it. Made on first use. */
@Entity
@Table(name = "reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_cycle_employee", columnNames = {"cycle_id", "employee_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "self_intro", length = 4000)
    private String selfIntro;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
