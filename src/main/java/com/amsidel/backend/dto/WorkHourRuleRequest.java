/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** One brand's default hours, or its hours for one designation. */
@Data
public class WorkHourRuleRequest {

    /** Empty = the group-level people, who have no brand. */
    private Long brandId;

    /** Empty = the brand's default. */
    private Long designationId;

    @NotNull(message = "Give the hours a day")
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal dailyMinHours;

    @NotNull(message = "Give the hours a week")
    @DecimalMin("0")
    @DecimalMax("168")
    private BigDecimal weeklyMinHours;

    @NotNull(message = "Give the Half Day Leave limit")
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal halfDayBelow;

    @NotNull(message = "Give the Full Day Leave limit")
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal fullDayBelow;
}
