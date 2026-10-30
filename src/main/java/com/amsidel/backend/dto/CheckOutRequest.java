/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Closing the open entry ("End work today" or a field visit): where, and an optional note. No photo, no time field. */
@Data
public class CheckOutRequest {

    @DecimalMin("-90") @DecimalMax("90")
    private Double latitude;

    @DecimalMin("-180") @DecimalMax("180")
    private Double longitude;

    /** Metres. */
    @PositiveOrZero
    private Double accuracy;

    /** Meeting status or how the day went. */
    @Size(max = 500)
    private String note;
}
