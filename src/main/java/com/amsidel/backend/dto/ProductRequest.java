package com.amsidel.backend.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
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
