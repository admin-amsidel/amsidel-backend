/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Checked-in time against the work hour rule, day by day: working days up to today, in minutes. Time still waiting for
 * approval is not called short, since it may yet count; today is listed but left out of the totals, as it is not over.
 * Sundays, holidays, whole days of approved leave and days before joining need nothing; half a day's leave needs half.
 */
public record AttendanceShortfall(BigDecimal dailyMinHours, String source, long requiredMinutes, long workedMinutes, long waitingMinutes,
                                  long shortMinutes, List<Row> days) {

    public record Row(LocalDate date, boolean today, long requiredMinutes, long workedMinutes, long waitingMinutes, long shortMinutes) {
    }
}
