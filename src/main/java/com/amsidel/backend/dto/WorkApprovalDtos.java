/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.WorkMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The approver's queue: attendance and work log entries waiting for a decision. */
public final class WorkApprovalDtos {

    private WorkApprovalDtos() {
    }

    public record Person(Long id, String name, String empNumber, String designationName, String brandCode, String photoPath, Instant photoUpdatedAt) {
    }

    /** decidable false: still checked in today, so there is no time to approve yet. */
    public record AttendanceItem(Long id, Person person, LocalDate date, WorkMode workMode, String place, Instant checkIn, Instant checkOut,
                                 Long minutes, String note, boolean hasPhoto, boolean decidable) {
    }

    public record WorkLogItem(Long id, Person person, LocalDate date, String taskId, String taskTitle, BigDecimal hours, String description,
                              ManualSalesDtos.EntryView manualSales) {
    }

    public record Queue(List<AttendanceItem> attendance, List<WorkLogItem> workLogs) {
    }

    /** One decision for any number of entries. */
    public record Decision(List<Long> attendanceIds, List<Long> workLogIds, @NotNull(message = "Say whether to approve or reject") Boolean approve,
                           @Size(max = 500, message = "The note can be at most 500 characters") String note) {
    }

    public record Decided(int count) {
    }
}
