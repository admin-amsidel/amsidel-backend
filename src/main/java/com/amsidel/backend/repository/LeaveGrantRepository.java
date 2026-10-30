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

    @Query("select g from LeaveGrant g join fetch g.leaveType join fetch g.employee join fetch g.grantedBy"
            + " where g.employee.id = :employeeId and g.leaveYear = :year order by g.createdAt desc")
    List<LeaveGrant> forEmployee(@Param("employeeId") Long employeeId, @Param("year") int year);
}
