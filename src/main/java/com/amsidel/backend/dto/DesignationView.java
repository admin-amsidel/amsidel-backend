/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Designation;

/** A designation with the brand it belongs to (brandId null = group-wide, for every brand). */
public record DesignationView(Long id, String name, String shortCode, String description, int levelNumber, String levelCode, boolean active,
                              Long brandId, String brandCode, String brandName) {

    /** Call inside a transaction: reads the brand. */
    public static DesignationView of(Designation d) {
        return new DesignationView(d.getId(), d.getName(), d.getShortCode(), d.getDescription(), d.getLevelNumber(), d.getLevelCode(), d.isActive(),
                d.getBrand() == null ? null : d.getBrand().getId(),
                d.getBrand() == null ? null : d.getBrand().getCode(),
                d.getBrand() == null ? null : d.getBrand().getName());
    }
}
