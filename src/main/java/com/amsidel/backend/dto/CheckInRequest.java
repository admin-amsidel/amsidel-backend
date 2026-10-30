/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.WorkMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The form fields sent with the check-in photo (multipart). Location is optional: without it the check-in is
 * still taken and flagged LOCATION_MISSING. There is no time field: the server sets the time.
 */
@Data
public class CheckInRequest {

    @NotNull(message = "Choose Office, Work from home or Field")
    private WorkMode workMode;

    @DecimalMin("-90") @DecimalMax("90")
    private Double latitude;

    @DecimalMin("-180") @DecimalMax("180")
    private Double longitude;

    /** Metres. */
    @PositiveOrZero
    private Double accuracy;

    /** The place or client: required for field visits. */
    @Size(max = 120)
    private String place;

    @Size(max = 40)
    private String platform;

    @Size(max = 60)
    private String osVersion;

    @Size(max = 40)
    private String appVersion;
}
