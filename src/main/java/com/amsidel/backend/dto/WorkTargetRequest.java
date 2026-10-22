package com.amsidel.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/** One person's own minimum hours; leave a value empty (null) to use their level's rule again. */
@Data
public class WorkTargetRequest {

    @DecimalMin("0")
    @DecimalMax("24")
    private BigDecimal dailyMinHours;

    @DecimalMin("0")
    @DecimalMax("168")
    private BigDecimal weeklyMinHours;
}
