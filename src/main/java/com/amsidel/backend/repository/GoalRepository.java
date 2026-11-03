/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    @Query("select distinct g from Goal g left join fetch g.tasks left join fetch g.managerRatedBy"
            + " where g.review.id = :reviewId order by g.startDate, g.id")
    List<Goal> forReview(@Param("reviewId") Long reviewId);

    @Query("select distinct g from Goal g left join fetch g.tasks where g.review.id in :reviewIds")
    List<Goal> forReviews(@Param("reviewIds") Collection<Long> reviewIds);

    @Query("select g from Goal g join fetch g.review r join fetch r.cycle join fetch r.employee e left join fetch e.brand"
            + " left join fetch e.reportsTo where g.id = :id")
    Optional<Goal> findFull(@Param("id") Long id);

    @Query("select coalesce(sum(g.weightage), 0) from Goal g where g.review.id = :reviewId and (:exceptId is null or g.id <> :exceptId)")
    int weightOf(@Param("reviewId") Long reviewId, @Param("exceptId") Long exceptId);

    /** These people's task worklog goals in cycles still open. */
    @Query("select distinct g from Goal g join fetch g.review r join fetch r.cycle c join fetch r.employee left join fetch g.tasks"
            + " where g.type = com.amsidel.backend.entity.GoalType.TASK_WORKLOG and c.closed = false and r.employee.id in :employeeIds")
    List<Goal> openWorklogGoals(@Param("employeeIds") Collection<Long> employeeIds);

    /** The goal a sales achievement record belongs to. */
    @Query("select e.goal.id from SalesEntry e where e.id = :entryId")
    Optional<Long> goalOfSalesEntry(@Param("entryId") Long entryId);
}
