package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BrandRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "Code must be 2-10 letters or numbers, e.g. DW")
    private String code;

    @NotBlank
    @Size(max = 150)
    private String name;

    private Boolean active;
}
