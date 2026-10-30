/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.LeaveRequest;
import com.amsidel.backend.entity.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    @Query("select r from LeaveRequest r join fetch r.leaveType join fetch r.employee e left join fetch e.brand"
            + " left join fetch r.decidedBy where r.id = :id")
    Optional<LeaveRequest> findFull(@Param("id") Long id);

    /** One person's requests in a year, newest first. */
    @Query("select r from LeaveRequest r join fetch r.leaveType left join fetch r.decidedBy"
            + " where r.employee.id = :employeeId and r.leaveYear = :year order by r.fromDate desc")
    List<LeaveRequest> forEmployee(@Param("employeeId") Long employeeId, @Param("year") int year);

    /** Requests for approvers: one status (or all), one brand (or all). */
    @Query("select r from LeaveRequest r join fetch r.leaveType join fetch r.employee e left join fetch e.brand b"
            + " left join fetch r.decidedBy"
            + " where (:status is null or r.status = :status) and (:brandId is null or b.id = :brandId)"
            + " order by r.fromDate desc")
    List<LeaveRequest> search(@Param("status") LeaveStatus status, @Param("brandId") Long brandId);

    /** One person's leave touching a date range, for the work log calendar. */
    @Query("select r from LeaveRequest r join fetch r.leaveType where r.employee.id = :employeeId"
            + " and r.status in :statuses and r.fromDate <= :to and r.toDate >= :from order by r.fromDate")
    List<LeaveRequest> overlapping(@Param("employeeId") Long employeeId, @Param("statuses") Collection<LeaveStatus> statuses,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(r) from LeaveRequest r where r.employee.id = :employeeId and r.status in :statuses"
            + " and r.fromDate <= :to and r.toDate >= :from")
    long countOverlapping(@Param("employeeId") Long employeeId, @Param("statuses") Collection<LeaveStatus> statuses,
                          @Param("from") LocalDate from, @Param("to") LocalDate to);
}
