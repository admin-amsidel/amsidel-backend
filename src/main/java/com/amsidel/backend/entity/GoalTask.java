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

/** A sub-task of a goal: its weight in the goal (the goal's tasks add up to 100%), dates and % done. */
@Entity
@Table(name = "goal_tasks")
@Getter
@Setter
@NoArgsConstructor
public class GoalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @Column(nullable = false, length = 150)
    private String title;

    /** % of the goal, 1-100. */
    @Column(nullable = false)
    private int weightage;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** % done, 0-100. */
    @Column(nullable = false)
    private int progress;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
