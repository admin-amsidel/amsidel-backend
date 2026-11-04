/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Minimum working hours for one brand: its default (designationKey 0), or for one designation in it. Below halfDayBelow
 * hours a day is flagged "Half Day Leave", below fullDayBelow "Full Day Leave".
 */
@Entity
@Table(name = "work_hour_brand_rules", uniqueConstraints = @UniqueConstraint(name = "uk_work_hour_brand_rule", columnNames = {"brand_key", "designation_key"}))
@Getter
@Setter
@NoArgsConstructor
public class BrandWorkRule {

    /** brandKey of the group-level people, who have no brand; their default is what a brand without one uses. */
    public static final long NO_BRAND = 0L;
    /** designationKey of a brand's default. */
    public static final long DEFAULT = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_key", nullable = false)
    private Long brandKey;

    @Column(name = "designation_key", nullable = false)
    private Long designationKey;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal dailyMinHours;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal weeklyMinHours;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal halfDayBelow;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal fullDayBelow;
}
