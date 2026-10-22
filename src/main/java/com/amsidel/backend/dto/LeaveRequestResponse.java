package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.LeaveRequest;
import com.amsidel.backend.entity.LeaveStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A leave request; available = the person's remaining balance of that type (approver lists only). */
public record LeaveRequestResponse(
        Long id,
        Long employeeId,
        String employeeName,
        String empNumber,
        String brandCode,
        Long leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        LocalDate fromDate,
        LocalDate toDate,
        boolean halfDay,
        BigDecimal days,
        String reason,
        LeaveStatus status,
        String decidedByName,
        Instant decidedAt,
        String decisionNote,
        LocalDateTime createdAt,
        BigDecimal available) {

    /** Call inside a transaction: reads the employee, type and approver. */
    public static LeaveRequestResponse from(LeaveRequest r, BigDecimal available) {
        Employee e = r.getEmployee();
        return new LeaveRequestResponse(
                r.getId(),
                e.getId(),
                e.getName(),
                e.getEmpNumber(),
                e.getBrand() == null ? null : e.getBrand().getCode(),
                r.getLeaveType().getId(),
                r.getLeaveType().getCode(),
                r.getLeaveType().getName(),
                r.getFromDate(),
                r.getToDate(),
                r.isHalfDay(),
                r.getDays(),
                r.getReason(),
                r.getStatus(),
                r.getDecidedBy() == null ? null : r.getDecidedBy().getName(),
                r.getDecidedAt(),
                r.getDecisionNote(),
                r.getCreatedAt(),
                available);
    }
}
