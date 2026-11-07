/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ManualSalesEntry;
import com.amsidel.backend.entity.ManualSalesKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ManualSalesEntryRepository extends JpaRepository<ManualSalesEntry, Long> {

    String FULL = "select e from ManualSalesEntry e join fetch e.workLog w join fetch e.employee p join fetch e.salesTask s join fetch s.task"
            + " left join fetch e.brand ";

    Optional<ManualSalesEntry> findByWorkLogId(Long workLogId);

    @Modifying
    @Query("delete from ManualSalesEntry e where e.workLog.id = :workLogId")
    void deleteForLog(@Param("workLogId") Long workLogId);

    @Query(FULL + "where w.id in :logIds")
    List<ManualSalesEntry> forLogs(@Param("logIds") Collection<Long> logIds);

    /** Whatever their approval, newest first; the details page filters further. */
    @Query(FULL + "where p.id in :employeeIds and w.workDate between :from and :to order by w.workDate desc, w.sequence desc, e.id desc")
    List<ManualSalesEntry> forPeople(@Param("employeeIds") Collection<Long> employeeIds, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Only what counts: approved, or from before approvals. */
    @Query(FULL + "where p.id = :employeeId and w.workDate between :from and :to"
            + " and (w.approval is null or w.approval = com.amsidel.backend.entity.ApprovalStatus.APPROVED) and e.kind is not null"
            + " order by w.workDate desc, w.sequence desc, e.id desc")
    List<ManualSalesEntry> approvedFor(@Param("employeeId") Long employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(FULL + "where s.taskId in :taskIds order by w.workDate desc, w.sequence desc, e.id desc")
    List<ManualSalesEntry> forTasks(@Param("taskIds") Collection<Long> taskIds);

    /** The same paper entered again would count twice; a rejected entry does not hold its number. */
    @Query("select count(e) from ManualSalesEntry e join e.workLog w where e.kind = :kind and upper(e.documentNumber) = upper(:number)"
            + " and coalesce(e.brand.id, -1) = :brandId and e.id <> :exceptId"
            + " and (w.approval is null or w.approval <> com.amsidel.backend.entity.ApprovalStatus.REJECTED)")
    long sameNumber(@Param("kind") ManualSalesKind kind, @Param("number") String number, @Param("brandId") long brandId, @Param("exceptId") long exceptId);
}
