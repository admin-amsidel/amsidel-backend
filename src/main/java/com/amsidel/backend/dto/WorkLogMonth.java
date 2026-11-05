/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the work log calendar needs for one person and date range: their target, their entries and the
 * leave (approved or waiting) that touches the range. editable = the signed-in person may change the entries.
 */
public record WorkLogMonth(
        Long employeeId,
        String employeeName,
        String empNumber,
        /** Days before this are not counted (no minimum, no warnings). */
        LocalDate joinedDate,
        LocalDate from,
        LocalDate to,
        LocalDate today,
        WorkTarget target,
        boolean editable,
        List<WorkLogEntry> entries,
        List<LeaveSpan> leave,
        /** Office holidays in the range: no hours expected. */
        List<Holiday> holidays,
        /** Checked-in time on each day that has any. */
        List<DayAttendance> attendance,
        /** This person has the Check attendance against work log tick. */
        boolean hoursCheck) {

    /**
     * Minutes approved, and waiting for approval; open: still checked in today, with the time so far in those figures;
     * waiting: an entry is with the approver, even one with no minutes yet.
     */
    public record DayAttendance(LocalDate date, long minutes, long waitingMinutes, boolean open, boolean waiting) {
    }

    /** Leave covering fromDate..toDate (Sundays inside a span are weekly offs, not leave). */
    public record Holiday(LocalDate date, String name) {
    }

    public record LeaveSpan(LocalDate fromDate, LocalDate toDate, boolean halfDay, String typeCode, String typeName,
                            String status) {
    }
}
