/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Minimum working hours for one designation level (levelNumber 1 = Trainee ... 10 = CEO); level 0 is the default
 * for everyone else. Below halfDayBelow hours a day is flagged "Half Day Leave", below fullDayBelow "Full Day Leave".
 */
@Entity
@Table(name = "work_hour_rules")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class WorkHourRule {

    /** Level 0 = everyone whose level has no rule of its own. */
    public static final int DEFAULT_LEVEL = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private int levelNumber;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal dailyMinHours;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal weeklyMinHours;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal halfDayBelow;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal fullDayBelow;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
