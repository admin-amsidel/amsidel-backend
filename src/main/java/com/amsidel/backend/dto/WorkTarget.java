package com.amsidel.backend.dto;

import java.math.BigDecimal;

/**
 * The hours a person is expected to log: dailyMinHours on each working day, weeklyMinHours per week (Monday to
 * Sunday). A working day below halfDayBelow is flagged "Half Day Leave", below fullDayBelow "Full Day Leave".
 * source says where the numbers come from, e.g. "Personal target" or "Level 4 rule".
 */
public record WorkTarget(
        BigDecimal dailyMinHours,
        BigDecimal weeklyMinHours,
        BigDecimal halfDayBelow,
        BigDecimal fullDayBelow,
        String source) {
}
