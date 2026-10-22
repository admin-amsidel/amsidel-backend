package com.amsidel.backend.dto;

import com.amsidel.backend.entity.LeaveAllocation;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class LeaveTypeRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{2,10}$", message = "Code must be 2-10 letters, e.g. CL")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    private LeaveAllocation allocation;

    /** Required when allocation = ANNUAL. */
    @Min(0)
    @Max(365)
    private Integer annualQuota;

    private Boolean halfDayAllowed;

    private Boolean restrictedDatesOnly;

    private Boolean active;

    private Integer sortOrder;
}
