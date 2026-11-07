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

    @Query("select distinct t from WorkTask t left join fetch t.assignees left join fetch t.brand left join fetch t.customer"
            + " left join fetch t.createdBy where (:status is null or t.status = :status) and (t.manualSales is null or t.manualSales = false)"
            + " order by t.createdAt desc")
    List<WorkTask> search(@Param("status") TaskStatus status);

    /** The Manual Sales worklog's tasks. */
    @Query("select distinct t from WorkTask t left join fetch t.assignees left join fetch t.brand left join fetch t.createdBy"
            + " where (:status is null or t.status = :status) and t.manualSales = true order by t.code desc")
    List<WorkTask> searchManual(@Param("status") TaskStatus status);

    /** Tasks for this customer (a customer that has any cannot be deleted). */
    boolean existsByCustomerId(Long customerId);

    @Query("select t from WorkTask t left join fetch t.assignees where t.id = :id")
    Optional<WorkTask> findFull(@Param("id") Long id);

    @Query("select t from WorkTask t left join fetch t.assignees where upper(t.code) = upper(:code)")
    Optional<WorkTask> findByCode(@Param("code") String code);

    /** Open tasks assigned to one person, by code. */
    @Query("select t from WorkTask t join t.assignees a where a.id = :employeeId and t.status = :status order by t.code")
    List<WorkTask> assignedTo(@Param("employeeId") Long employeeId, @Param("status") TaskStatus status);

    /** Every task assigned to one person, whatever its status (the sub-tasks of their task worklog goal, which never has Manual Sales ones). */
    @Query("select t from WorkTask t join t.assignees a where a.id = :employeeId and (t.manualSales is null or t.manualSales = false) order by t.code")
    List<WorkTask> allAssignedTo(@Param("employeeId") Long employeeId);

    /** Tasks this person has marked complete on their task worklog goals. */
    @Query("select distinct t.workTask.id from GoalTask t where t.goal.review.employee.id = :employeeId"
            + " and t.workTask is not null and t.completedAt is not null")
    List<Long> markedCompleteBy(@Param("employeeId") Long employeeId);

    /** {employee id, task} for everyone in a team, by code. */
    @Query("select a.id, t from WorkTask t join t.assignees a where a.id in :ids order by t.code")
    List<Object[]> assignedToPeople(@Param("ids") java.util.Collection<Long> ids);

    /** {employee id, task id} of the tasks a team has marked complete on their task worklog goals. */
    @Query("select t.goal.review.employee.id, t.workTask.id from GoalTask t where t.goal.review.employee.id in :ids"
            + " and t.workTask is not null and t.completedAt is not null")
    List<Object[]> markedCompleteByPeople(@Param("ids") java.util.Collection<Long> ids);

    boolean existsByCodeIgnoreCase(String code);

    /** Highest generated code (TSK-0007), used to make the next one. */
    Optional<WorkTask> findTopByCodeStartingWithOrderByCodeDesc(String prefix);
}
