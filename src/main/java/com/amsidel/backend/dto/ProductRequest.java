package com.amsidel.backend.dto;

import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Data
public class ProductRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @Size(max = 100)
    private String category;

    @Size(max = 1000)
    private String description;

    @DecimalMin("0.0")
    private BigDecimal price;

    private Boolean active;
}
