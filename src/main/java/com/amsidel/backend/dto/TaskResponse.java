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

/** A task with its assignees, the customer it is for (if any) and the hours logged against it so far. */
public record TaskResponse(
        Long id,
        String code,
        String title,
        String description,
        TaskStatus status,
        Long brandId,
        String brandName,
        Long customerId,
        String customerCode,
        String customerName,
        List<Assignee> assignees,
        BigDecimal loggedHours,
        String createdByName,
        LocalDateTime createdAt) {

    public record Assignee(Long id, String name, String empNumber, String photoPath, Instant photoUpdatedAt) {
        static Assignee from(Employee e) {
            return new Assignee(e.getId(), e.getName(), e.getEmpNumber(), e.getPhotoPath(), e.getPhotoUpdatedAt());
        }
    }

    /** One work log entry against a task: who, which day, how long and what was done. */
    public record LogEntry(Long id, Long employeeId, String employeeName, String empNumber, String photoPath,
                           Instant photoUpdatedAt, java.time.LocalDate workDate, BigDecimal hours, String description,
                           LocalDateTime createdAt) {
        /** Call inside a transaction: reads the employee. */
        public static LogEntry from(com.amsidel.backend.entity.WorkLog w) {
            Employee e = w.getEmployee();
            return new LogEntry(w.getId(), e.getId(), e.getName(), e.getEmpNumber(), e.getPhotoPath(), e.getPhotoUpdatedAt(),
                    w.getWorkDate(), w.getHours(), w.getDescription(), w.getCreatedAt());
        }
    }

    /** A task and every work log entry against it (newest day first). */
    public record TaskLogs(TaskResponse task, List<LogEntry> entries) {
    }

    /** A customer a task can be for (the Customer ID picker of the task form). */
    public record CustomerOption(Long id, String code, String name, String phone, Long brandId, String brandCode, boolean active) {
    }

    /** Call inside a transaction: reads the brand, customer, assignees and creator. */
    public static TaskResponse from(WorkTask t, BigDecimal loggedHours) {
        return new TaskResponse(
                t.getId(),
                t.getCode(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getBrand() == null ? null : t.getBrand().getId(),
                t.getBrand() == null ? null : t.getBrand().getName(),
                t.getCustomer() == null ? null : t.getCustomer().getId(),
                t.getCustomer() == null ? null : t.getCustomer().getCode(),
                t.getCustomer() == null ? null : t.getCustomer().getName(),
                t.getAssignees().stream().sorted(Comparator.comparing(Employee::getName)).map(Assignee::from).toList(),
                loggedHours,
                t.getCreatedBy().getName(),
                t.getCreatedAt());
    }
}
