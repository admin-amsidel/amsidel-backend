/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DesignationRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    @Min(1)
    @Max(99)
    private Integer levelNumber;

    /** e.g. "4" or "6.1": the level number, optionally followed by a dot and a sub-level. */
    @NotBlank
    @Pattern(regexp = "^[0-9]{1,2}(\\.[0-9]{1,2})?$", message = "Level must look like 4 or 6.1")
    private String levelCode;

    private Boolean active;
}
