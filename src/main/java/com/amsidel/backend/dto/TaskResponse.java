/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.entity.WorkTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** A task with its assignees and the hours logged against it so far. */
public record TaskResponse(
        Long id,
        String code,
        String title,
        String description,
        TaskStatus status,
        Long brandId,
        String brandName,
        List<Assignee> assignees,
        BigDecimal loggedHours,
        String createdByName,
        LocalDateTime createdAt) {

    public record Assignee(Long id, String name, String empNumber, String photoPath, Instant photoUpdatedAt) {
        static Assignee from(Employee e) {
            return new Assignee(e.getId(), e.getName(), e.getEmpNumber(), e.getPhotoPath(), e.getPhotoUpdatedAt());
        }
    }

    /** Call inside a transaction: reads the brand, assignees and creator. */
    public static TaskResponse from(WorkTask t, BigDecimal loggedHours) {
        return new TaskResponse(
                t.getId(),
                t.getCode(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getBrand() == null ? null : t.getBrand().getId(),
                t.getBrand() == null ? null : t.getBrand().getName(),
                t.getAssignees().stream().sorted(Comparator.comparing(Employee::getName)).map(Assignee::from).toList(),
                loggedHours,
                t.getCreatedBy().getName(),
                t.getCreatedAt());
    }
}
