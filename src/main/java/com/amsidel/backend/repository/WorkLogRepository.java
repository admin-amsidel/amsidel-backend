/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.WorkLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface WorkLogRepository extends JpaRepository<WorkLog, Long> {

    /** One person's entries between two dates (both included), by day and then by their order in the day. */
    @Query("select w from WorkLog w left join fetch w.task where w.employee.id = :employeeId"
            + " and w.workDate between :from and :to order by w.workDate, w.sequence")
    List<WorkLog> forEmployee(@Param("employeeId") Long employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Hours logged per task: rows of [task id, total hours]. */
    @Query("select w.task.id, sum(w.hours) from WorkLog w where w.task is not null group by w.task.id")
    List<Object[]> hoursPerTask();

    @Query("select coalesce(max(w.sequence), 0) from WorkLog w where w.employee.id = :employeeId and w.workDate = :day")
    int lastSequence(@Param("employeeId") Long employeeId, @Param("day") LocalDate day);

    @Query("select coalesce(sum(w.hours), 0) from WorkLog w where w.employee.id = :employeeId and w.workDate = :day"
            + " and w.id <> :exceptId")
    BigDecimal hoursOn(@Param("employeeId") Long employeeId, @Param("day") LocalDate day, @Param("exceptId") Long exceptId);
}
