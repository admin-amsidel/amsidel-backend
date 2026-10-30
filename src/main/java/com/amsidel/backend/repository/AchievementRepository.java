/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {

    /** Newest first. */
    @Query("select a from Achievement a left join fetch a.issuedBy where a.employee.id = :employeeId"
            + " and a.achievedOn between :from and :to order by a.achievedOn desc, a.id desc")
    List<Achievement> forEmployee(@Param("employeeId") Long employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select a from Achievement a join fetch a.employee e left join fetch e.brand left join fetch e.reportsTo"
            + " left join fetch a.issuedBy where a.id = :id")
    Optional<Achievement> findFull(@Param("id") Long id);
}
