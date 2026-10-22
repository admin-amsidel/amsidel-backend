package com.amsidel.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Adds days to one person's balance: comp-off, paternity leave or extra days. */
@Data
public class LeaveGrantRequest {

    @NotNull
    private Long employeeId;

    @NotNull
    private Long leaveTypeId;

    @NotNull
    @DecimalMin(value = "0.5", message = "Grant at least half a day")
    @DecimalMax(value = "60", message = "Grant at most 60 days at a time")
    private BigDecimal days;

    /** Year the days can be used in; defaults to the year of workedOn, else the current year. */
    @Min(2020)
    @Max(2100)
    private Integer year;

    /** Comp-off: the holiday or Sunday that was worked. */
    private LocalDate workedOn;

    @NotBlank
    @Size(max = 300)
    private String reason;
}
