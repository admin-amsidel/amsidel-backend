package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RestrictedHolidayRequest {

    @NotNull
    private LocalDate date;

    @NotBlank
    @Size(max = 100)
    private String name;

    /** Empty = every brand. */
    private Long brandId;
}
