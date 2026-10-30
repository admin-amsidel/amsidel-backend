/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A goal a manager sets for someone in a review: its weight in the review (a person's goals add up to 100%), its
 * dates, and its sub-tasks (their weights add up to 100% of the goal). After the end date the person rates it
 * (0.5-5 in half stars), then the manager rates it (0.5-5) with written feedback.
 */
@Entity
@Table(name = "goals")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 2000)
    private String description;

    /** % of the review, 1-100. */
    @Column(nullable = false)
    private int weightage;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** % done, for a goal without sub-tasks (otherwise worked out from them). */
    @Column(nullable = false)
    private int progress;

    @OneToMany(mappedBy = "goal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<GoalTask> tasks = new ArrayList<>();

    /** 0.5-5 in half stars. */
    @Column(name = "self_rating", precision = 3, scale = 1)
    private java.math.BigDecimal selfRating;

    @Column(name = "self_comment", length = 2000)
    private String selfComment;

    @Column(name = "self_rated_at")
    private Instant selfRatedAt;

    /** 0.5-5 in half stars. */
    @Column(name = "manager_rating", precision = 3, scale = 1)
    private java.math.BigDecimal managerRating;

    @Column(name = "manager_comment", length = 2000)
    private String managerComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_rated_by_id")
    private Employee managerRatedBy;

    @Column(name = "manager_rated_at")
    private Instant managerRatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private Employee createdBy;

    @CreatedDate
    @Column(updatable = false)
    private java.time.LocalDateTime createdAt;
}
