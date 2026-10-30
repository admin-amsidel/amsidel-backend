/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.entity.WorkTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkTaskRepository extends JpaRepository<WorkTask, Long> {

    @Query("select distinct t from WorkTask t left join fetch t.assignees left join fetch t.brand"
            + " left join fetch t.createdBy where (:status is null or t.status = :status) order by t.createdAt desc")
    List<WorkTask> search(@Param("status") TaskStatus status);

    @Query("select t from WorkTask t left join fetch t.assignees where t.id = :id")
    Optional<WorkTask> findFull(@Param("id") Long id);

    @Query("select t from WorkTask t left join fetch t.assignees where upper(t.code) = upper(:code)")
    Optional<WorkTask> findByCode(@Param("code") String code);

    /** Open tasks assigned to one person, by code. */
    @Query("select t from WorkTask t join t.assignees a where a.id = :employeeId and t.status = :status order by t.code")
    List<WorkTask> assignedTo(@Param("employeeId") Long employeeId, @Param("status") TaskStatus status);

    boolean existsByCodeIgnoreCase(String code);

    /** Highest generated code (TSK-0007), used to make the next one. */
    Optional<WorkTask> findTopByCodeStartingWithOrderByCodeDesc(String prefix);
}
