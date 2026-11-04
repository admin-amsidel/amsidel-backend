/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.LeavePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LeavePlanRepository extends JpaRepository<LeavePlan, Long> {

    /** Every plan with its brand and lines, earliest first (few rows: a brand has one or two a year). */
    @Query("select distinct p from LeavePlan p left join fetch p.brand left join fetch p.lines l left join fetch l.leaveType order by p.validFrom")
    List<LeavePlan> allFull();
}
