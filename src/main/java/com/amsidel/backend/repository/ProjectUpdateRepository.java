/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ProjectUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Project status updates, newest first. */
public interface ProjectUpdateRepository extends JpaRepository<ProjectUpdate, Long> {

    @Query("select distinct u from ProjectUpdate u left join fetch u.photos left join fetch u.createdBy left join fetch u.updatedBy"
            + " where u.project.id = :projectId order by u.happenedAt desc, u.id desc")
    List<ProjectUpdate> forProject(@Param("projectId") Long projectId);

    @Query("select u from ProjectUpdate u join fetch u.project p join fetch p.brand where u.id = :id")
    Optional<ProjectUpdate> findFull(@Param("id") Long id);

    /** [projectId, count, latest happenedAt] for these projects. */
    @Query("select u.project.id, count(u), max(u.happenedAt) from ProjectUpdate u where u.project.id in :ids group by u.project.id")
    List<Object[]> summary(@Param("ids") Collection<Long> projectIds);
}
