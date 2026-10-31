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
 * A sales goal (type SALES) has a target amount split over its sub-tasks; the person records what they achieved
 * (SalesEntry), and its progress and self rating follow achieved / target.
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

    /** GENERAL or SALES; null (goals set before types came) = GENERAL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", length = 12)
    private GoalType type;

    /** Sales goals: the amount to achieve. */
    @Column(name = "target_amount", precision = 14, scale = 2)
    private java.math.BigDecimal targetAmount;

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

    /** Sales goals: what the person achieved, oldest first. */
    @OneToMany(mappedBy = "goal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("fromDate, id")
    private List<SalesEntry> salesEntries = new ArrayList<>();

    public boolean isSales() {
        return type == GoalType.SALES;
    }

    /** Reached by the person's confirmed sales orders (worked out when read, never typed in). */
    public boolean isOrders() {
        return type == GoalType.SALES_ORDER;
    }

    /** Sales-order goals: the confirmed orders counted, filled in when the goal is read (not stored). */
    @Transient
    private List<SalesDoc> orders = List.of();

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
