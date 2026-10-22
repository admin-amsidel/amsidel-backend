package com.amsidel.backend.repository;

import com.amsidel.backend.entity.OfficeHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OfficeHolidayRepository extends JpaRepository<OfficeHoliday, Long> {

    @Query("select h from OfficeHoliday h left join fetch h.brand"
            + " where h.holidayDate between :from and :to order by h.holidayDate")
    List<OfficeHoliday> between(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
