/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** A project's tasks, in their order. */
public interface ProjectTaskRepository extends JpaRepository<ProjectTask, Long> {

    @Query("select t from ProjectTask t left join fetch t.assignee left join fetch t.bill left join fetch t.updatedBy"
            + " where t.project.id = :projectId order by t.sortOrder, t.id")
    List<ProjectTask> forProject(@Param("projectId") Long projectId);
}
