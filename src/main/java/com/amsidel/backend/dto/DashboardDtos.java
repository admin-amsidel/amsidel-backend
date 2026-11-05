/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The Overview page, part by part: each is asked for on its own. A part is empty (mail: no rows) when the person does not
 * have the menu behind it, so the page never shows what the menu's own page would refuse.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** The page's first answer. hasTeam: the Team view is worth offering. */
    public record Summary(LocalDate today, boolean hasTeam, Today day) {
    }

    /** kind: WORKING, WEEKLY_OFF, HOLIDAY or LEAVE (a whole day's approved leave); label: the holiday's or the leave's name. */
    public record Today(String kind, String label, boolean halfDayLeave) {
    }

    /** Hours in the work log: today's, and Monday to Sunday of this week. A target of null = none set. */
    public record Hours(BigDecimal today, BigDecimal dailyTarget, BigDecimal week, BigDecimal weeklyTarget, List<DayHours> days) {
    }

    /** off: a weekly off, holiday, whole day's approved leave or a day before joining. */
    public record DayHours(LocalDate date, BigDecimal hours, boolean off) {
    }

    /**
     * Checked-in time today, against the hours expected today; percent null on a day with none expected and no check-in.
     * week and month: the working days of each, whole periods, after joining. Only approved time counts; waiting: today's
     * check-in is with the approver.
     */
    public record Attendance(boolean checkedIn, boolean working, Instant firstIn, long minutesToday, long targetMinutes, Integer percent,
                             Period week, Period month, boolean waiting) {
    }

    /** days: working days in the period; attended: of those, the days checked in; leave: of those not attended, approved leave. */
    public record Period(int days, int attended, BigDecimal leave) {
    }

    /** The leave plan in force. remaining and entitled leave out unlimited types; taken counts them. */
    public record Leave(LocalDate planFrom, LocalDate planUntil, BigDecimal entitled, BigDecimal taken, BigDecimal pending, BigDecimal remaining,
                        List<LeaveRow> types) {
    }

    /** entitled and available null = unlimited. */
    public record LeaveRow(String code, String name, BigDecimal entitled, BigDecimal used, BigDecimal pending, BigDecimal available) {
    }

    /** Tasks assigned to me. Completed: closed, or marked complete by me. */
    public record Tasks(int completed, int pending, List<TaskRow> pendingTasks) {
    }

    public record TaskRow(Long id, String code, String title) {
    }

    /** Take-home pay in this financial year, from published payslips: received, expected from the salary, and what is left. */
    public record Pay(String fyLabel, boolean salarySet, BigDecimal received, BigDecimal expected, BigDecimal remaining, int monthsPaid, int monthsInYear,
                      List<MonthNet> months) {
    }

    /** net null = no payslip seen for the month. */
    public record MonthNet(String label, BigDecimal net) {
    }

    /** One kind of mail: what the brand has sent today; limit and left null = no daily limit. scope: the brand's code, or All brands. */
    public record Mail(String kind, String scope, int sentToday, Integer limit, Integer left) {
    }

    public record Profile(int percent, int done, int total) {
    }

    /** The Team view's first answer. canSplit: the team can span brands, so brands is filled; brandId: the brand asked for. */
    public record TeamSummary(LocalDate today, int people, boolean canSplit, Long brandId, List<BrandOption> brands) {
    }

    /** Person by person, and brand by brand when the team spans brands and all of them are asked for. Pay comes with TeamPay. */
    public record TeamPeople(List<Member> members, List<BrandRow> byBrand) {
    }

    /** The team's pay, null with nobody in the team, and what each person and brand (by key, 0 = no brand) has received. */
    public record TeamPay(Pay pay, java.util.Map<Long, BigDecimal> receivedByMember, java.util.Map<Long, BigDecimal> receivedByBrand) {
    }

    /** id 0 = the people without a brand. */
    public record BrandOption(Long id, String code, String name) {
    }

    /** checkedIn of expected: people due at work today; percent null when nobody is. Periods add up everyone's days. */
    public record TeamAttendance(int checkedIn, int expected, Integer percent, int notExpected, Period week, Period month) {
    }

    /** complete of people have a full profile; percent is the average. */
    public record TeamProfile(int percent, int complete, int people) {
    }

    /** One person of the team; a figure is null when the viewer does not have the page behind it. status: CHECKED_IN, WAITING (with the approver), NOT_IN, ON_LEAVE or OFF. */
    public record Member(Long id, String name, String designationName, String brandCode, String photoPath, Instant photoUpdatedAt,
                         BigDecimal hoursToday, String status, Long minutesToday, Integer tasksCompleted, Integer tasksPending,
                         BigDecimal leaveTaken, BigDecimal leaveRemaining, int profilePercent) {
    }

    /** One brand of the team, to compare. */
    public record BrandRow(Long id, String code, String name, int people, BigDecimal hoursToday, Integer checkedIn, Integer expected,
                           Integer tasksCompleted, Integer tasksPending, BigDecimal leaveTaken, BigDecimal leaveRemaining, int profilePercent) {
    }
}
