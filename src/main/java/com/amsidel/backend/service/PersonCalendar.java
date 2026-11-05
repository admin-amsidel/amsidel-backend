/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.DashboardDtos.Period;
import com.amsidel.backend.dto.DashboardDtos.Today;
import com.amsidel.backend.dto.WorkLogMonth.LeaveSpan;
import com.amsidel.backend.entity.LeaveStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One person's calendar around today, for the dashboard: weekly offs, holidays, joining date and approved leave. */
final class PersonCalendar {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final String APPROVED = LeaveStatus.APPROVED.name();

    private final LocalDate today;
    private final LocalDate joined;
    private final BigDecimal dailyHours;
    private final List<LeaveSpan> leave;
    private final Map<LocalDate, String> holidays;
    private final Set<DayOfWeek> weeklyOffs;

    PersonCalendar(LocalDate today, LocalDate joined, BigDecimal dailyHours, List<LeaveSpan> leave, Map<LocalDate, String> holidays,
                   Set<DayOfWeek> weeklyOffs) {
        this.today = today;
        this.joined = joined;
        this.dailyHours = dailyHours;
        this.leave = leave;
        this.holidays = holidays;
        this.weeklyOffs = weeklyOffs;
    }

    static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static LocalDate sunday(LocalDate day) {
        return day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    boolean workingDay(LocalDate d) {
        return !weeklyOffs.contains(d.getDayOfWeek()) && !holidays.containsKey(d) && (joined == null || !d.isBefore(joined));
    }

    private LeaveSpan approvedLeave(LocalDate d) {
        return leave.stream()
                .filter(l -> APPROVED.equals(l.status()) && !d.isBefore(l.fromDate()) && !d.isAfter(l.toDate()))
                .findFirst().orElse(null);
    }

    boolean wholeDayLeave(LocalDate d) {
        LeaveSpan l = approvedLeave(d);
        return l != null && !l.halfDay();
    }

    boolean off(LocalDate d) {
        return !workingDay(d) || wholeDayLeave(d);
    }

    /** The time expected today: half on a half day's leave. */
    long targetMinutes() {
        if (dailyHours == null) {
            return 0;
        }
        long minutes = dailyHours.multiply(BigDecimal.valueOf(60)).longValue();
        return approvedLeave(today) != null ? minutes / 2 : minutes;
    }

    Today today() {
        LeaveSpan l = approvedLeave(today);
        if (weeklyOffs.contains(today.getDayOfWeek())) {
            return new Today("WEEKLY_OFF", null, false);
        }
        if (holidays.containsKey(today)) {
            return new Today("HOLIDAY", holidays.get(today), false);
        }
        if (l != null && !l.halfDay()) {
            return new Today("LEAVE", l.typeName(), false);
        }
        return new Today("WORKING", l == null ? null : l.typeName(), l != null);
    }

    Period period(LocalDate from, LocalDate to, Set<LocalDate> checkedIn) {
        int days = 0;
        int attended = 0;
        BigDecimal onLeave = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!workingDay(d)) {
                continue;
            }
            days++;
            if (checkedIn.contains(d)) {
                attended++;
            } else {
                LeaveSpan l = approvedLeave(d);
                if (l != null) {
                    onLeave = onLeave.add(l.halfDay() ? HALF : BigDecimal.ONE);
                }
            }
        }
        return new Period(days, attended, onLeave);
    }
}
