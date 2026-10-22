package com.amsidel.backend.service;

import com.amsidel.backend.entity.OfficeHoliday;
import com.amsidel.backend.repository.OfficeHolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** Which days are office holidays for someone: the group-wide ones plus their brand's (all of them without a brand). */
@Component
@RequiredArgsConstructor
public class OfficeHolidays {

    private final OfficeHolidayRepository repository;

    /** Date → holiday name, from..to included, in date order. */
    public Map<LocalDate, String> forBrand(Long brandId, LocalDate from, LocalDate to) {
        Map<LocalDate, String> days = new TreeMap<>();
        for (OfficeHoliday h : repository.between(from, to)) {
            if (h.getBrand() == null || brandId == null || h.getBrand().getId().equals(brandId)) {
                days.merge(h.getHolidayDate(), h.getName(), (a, b) -> a + " / " + b);
            }
        }
        return days;
    }
}
