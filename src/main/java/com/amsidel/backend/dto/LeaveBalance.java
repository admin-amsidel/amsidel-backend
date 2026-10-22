package com.amsidel.backend.dto;

import com.amsidel.backend.entity.LeaveAllocation;

import java.math.BigDecimal;

/**
 * One leave type's balance for one person and year. entitled = quota + granted; available = entitled - used
 * - pending. For UNLIMITED types (leave without pay) entitled and available are null.
 */
public record LeaveBalance(
        Long leaveTypeId,
        String code,
        String name,
        LeaveAllocation allocation,
        boolean halfDayAllowed,
        boolean restrictedDatesOnly,
        int quota,
        BigDecimal granted,
        BigDecimal entitled,
        BigDecimal used,
        BigDecimal pending,
        BigDecimal available) {
}
