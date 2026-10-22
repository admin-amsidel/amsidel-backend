package com.amsidel.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/** Minimum hours for one designation level; levelNumber 0 = the default for everyone else. */
@Data
public class WorkHourRuleRequest {

    @NotNull
    @Min(0)
    @Max(99)
    private Integer levelNumber;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal dailyMinHours;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("168")
    private BigDecimal weeklyMinHours;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal halfDayBelow;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal fullDayBelow;
}
