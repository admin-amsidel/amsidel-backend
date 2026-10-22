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
        List<LeaveSpan> leave) {

    /** Leave covering fromDate..toDate (Sundays inside a span are weekly offs, not leave). */
    public record LeaveSpan(LocalDate fromDate, LocalDate toDate, boolean halfDay, String typeCode, String typeName,
                            String status) {
    }
}
