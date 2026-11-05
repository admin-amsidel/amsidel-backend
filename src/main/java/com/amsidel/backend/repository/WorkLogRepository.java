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

    /** Everything logged against one task, with who logged it: newest day first. */
    @Query("select w from WorkLog w join fetch w.employee where w.task.id = :taskId order by w.workDate desc, w.sequence desc")
    List<WorkLog> forTask(@Param("taskId") Long taskId);

    /** Hours logged per task: rows of [task id, total hours]. */
    @Query("select w.task.id, sum(w.hours) from WorkLog w where w.task is not null and (w.approval is null or w.approval = com.amsidel.backend.entity.ApprovalStatus.APPROVED) group by w.task.id")
    List<Object[]> hoursPerTask();

    /** {employee id, day, hours} for everyone in a team. */
    @Query("select w.employee.id, w.workDate, sum(w.hours) from WorkLog w where w.employee.id in :ids and w.workDate between :from and :to and (w.approval is null or w.approval = com.amsidel.backend.entity.ApprovalStatus.APPROVED)"
            + " group by w.employee.id, w.workDate")
    List<Object[]> hoursPerDay(@Param("ids") java.util.Collection<Long> ids, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Entries waiting for a decision, with what the queue shows. */
    @Query("select w from WorkLog w join fetch w.employee e left join fetch e.brand left join fetch e.designation left join fetch e.reportsTo"
            + " left join fetch e.approver left join fetch w.task where w.approval = :status order by w.workDate, w.id")
    List<WorkLog> waiting(@Param("status") com.amsidel.backend.entity.ApprovalStatus status);

    @Query("select coalesce(max(w.sequence), 0) from WorkLog w where w.employee.id = :employeeId and w.workDate = :day")
    int lastSequence(@Param("employeeId") Long employeeId, @Param("day") LocalDate day);

    @Query("select coalesce(sum(w.hours), 0) from WorkLog w where w.employee.id = :employeeId and w.workDate = :day"
            + " and (w.approval is null or w.approval <> com.amsidel.backend.entity.ApprovalStatus.REJECTED)"
            + " and w.id <> :exceptId")
    BigDecimal hoursOn(@Param("employeeId") Long employeeId, @Param("day") LocalDate day, @Param("exceptId") Long exceptId);
}
