package com.amsidel.backend.dto;

import com.amsidel.backend.entity.LeaveGrant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveGrantResponse(
        Long id,
        Long employeeId,
        String employeeName,
        Long leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        int year,
        BigDecimal days,
        String reason,
        LocalDate workedOn,
        String grantedByName,
        LocalDateTime createdAt) {

    /** Call inside a transaction: reads the employee, type and granter. */
    public static LeaveGrantResponse from(LeaveGrant g) {
        return new LeaveGrantResponse(
                g.getId(),
                g.getEmployee().getId(),
                g.getEmployee().getName(),
                g.getLeaveType().getId(),
                g.getLeaveType().getCode(),
                g.getLeaveType().getName(),
                g.getLeaveYear(),
                g.getDays(),
                g.getReason(),
                g.getWorkedOn(),
                g.getGrantedBy().getName(),
                g.getCreatedAt());
    }
}
