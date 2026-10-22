package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveApplyRequest {

    @NotNull
    private Long leaveTypeId;

    @NotNull
    private LocalDate fromDate;

    /** Same as fromDate for one day (and for a half day). */
    @NotNull
    private LocalDate toDate;

    private boolean halfDay;

    @Size(max = 500)
    private String reason;
}
