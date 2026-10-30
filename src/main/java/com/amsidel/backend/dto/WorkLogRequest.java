/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One task worked on one day. */
@Data
public class WorkLogRequest {

    @NotNull
    private LocalDate workDate;

    /** The assigned task, e.g. MSC-1024. */
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._#/:-]+( [A-Za-z0-9._#/:-]+)*$",
            message = "Task ID may use letters, numbers and . _ # / : - (single spaces allowed)")
    private String taskId;

    @NotNull
    @DecimalMin(value = "0.25", message = "Log at least a quarter of an hour (0.25)")
    @DecimalMax(value = "24", message = "A day has at most 24 hours")
    @Digits(integer = 2, fraction = 2)
    private BigDecimal hours;

    @NotBlank
    @Size(max = 1000)
    private String description;
}
