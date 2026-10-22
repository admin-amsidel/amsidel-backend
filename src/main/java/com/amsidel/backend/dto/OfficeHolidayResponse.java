package com.amsidel.backend.dto;

import com.amsidel.backend.entity.OfficeHoliday;

import java.time.LocalDate;

public record OfficeHolidayResponse(Long id, LocalDate date, String name, Long brandId, String brandName) {

    /** Call inside a transaction: reads the brand. */
    public static OfficeHolidayResponse from(OfficeHoliday h) {
        return new OfficeHolidayResponse(
                h.getId(),
                h.getHolidayDate(),
                h.getName(),
                h.getBrand() == null ? null : h.getBrand().getId(),
                h.getBrand() == null ? null : h.getBrand().getName());
    }
}
