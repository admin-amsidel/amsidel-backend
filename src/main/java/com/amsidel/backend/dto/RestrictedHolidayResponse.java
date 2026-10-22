package com.amsidel.backend.dto;

import com.amsidel.backend.entity.RestrictedHoliday;

import java.time.LocalDate;

public record RestrictedHolidayResponse(Long id, LocalDate date, String name, Long brandId, String brandName) {

    /** Call inside a transaction: reads the brand. */
    public static RestrictedHolidayResponse from(RestrictedHoliday h) {
        return new RestrictedHolidayResponse(
                h.getId(),
                h.getHolidayDate(),
                h.getName(),
                h.getBrand() == null ? null : h.getBrand().getId(),
                h.getBrand() == null ? null : h.getBrand().getName());
    }
}
