/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.LeaveGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaveGrantRepository extends JpaRepository<LeaveGrant, Long> {

    @Query("select g from LeaveGrant g join fetch g.leaveType join fetch g.employee join fetch g.grantedBy left join fetch g.plan"
            + " where g.employee.id = :employeeId order by g.createdAt desc")
    List<LeaveGrant> forEmployee(@Param("employeeId") Long employeeId);

    /** A team's grants, as forEmployee gives one person's. */
    @Query("select g from LeaveGrant g join fetch g.leaveType join fetch g.employee left join fetch g.plan where g.employee.id in :ids order by g.createdAt desc")
    List<LeaveGrant> ofPeople(@Param("ids") java.util.Collection<Long> ids);
}
