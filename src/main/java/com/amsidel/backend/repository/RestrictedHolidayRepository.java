/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.RestrictedHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RestrictedHolidayRepository extends JpaRepository<RestrictedHoliday, Long> {

    @Query("select h from RestrictedHoliday h left join fetch h.brand"
            + " where h.holidayDate between :from and :to order by h.holidayDate")
    List<RestrictedHoliday> between(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
