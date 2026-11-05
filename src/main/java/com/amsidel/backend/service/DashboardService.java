/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AttendanceDaySummary;
import com.amsidel.backend.dto.AttendanceRecord;
import com.amsidel.backend.dto.DashboardDtos.*;
import com.amsidel.backend.dto.LeaveBalance;
import com.amsidel.backend.dto.LeavePlanDtos;
import com.amsidel.backend.dto.MeetingDtos;
import com.amsidel.backend.dto.PayslipDtos.MyPay;
import com.amsidel.backend.dto.WorkLogMonth;
import com.amsidel.backend.dto.WorkTarget;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.entity.WorkTask;
import com.amsidel.backend.repository.WorkTaskRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The signed-in person's Overview, put together from what the other pages already work out, so the numbers match them.
 * Each part is read on its own, so one slow part keeps no other waiting.
 * Each part follows the tick of the page behind it (My work log, Check in, My leave, My pay, the two kinds of email).
 * Each service runs its own transaction: nothing here writes.
 */
@Service
public class DashboardService {

    private final WorkLogService workLog;
    private final AttendanceService attendance;
    private final LeaveService leave;
    private final WorkTaskRepository tasks;
    private final PayslipService payslips;
    private final StaffMailConfig staffMail;
    private final TradeMailConfig tradeMail;
    private final EmployeeDocumentService documents;
    private final MeetingService meetings;
    private final MenuAccessService menuAccess;
    private final Set<DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public DashboardService(WorkLogService workLog, AttendanceService attendance, LeaveService leave, WorkTaskRepository tasks,
                            PayslipService payslips, StaffMailConfig staffMail, TradeMailConfig tradeMail, EmployeeDocumentService documents,
                            MeetingService meetings, MenuAccessService menuAccess,
                            @Value("${app.leave.weekly-offs}") Set<DayOfWeek> weeklyOffs, @Value("${app.leave.zone}") ZoneId zone) {
        this.workLog = workLog;
        this.attendance = attendance;
        this.leave = leave;
        this.tasks = tasks;
        this.payslips = payslips;
        this.staffMail = staffMail;
        this.tradeMail = tradeMail;
        this.documents = documents;
        this.meetings = meetings;
        this.menuAccess = menuAccess;
        this.weeklyOffs = weeklyOffs;
        this.zone = zone;
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private WorkLogMonth month(AuthUser me, LocalDate today) {
        return workLog.month(me, null, firstDay(today), lastDay(today));
    }

    private PersonCalendar calendar(LocalDate today, WorkLogMonth month) {
        Map<LocalDate, String> holidays = month.holidays().stream().collect(Collectors.toMap(WorkLogMonth.Holiday::date, WorkLogMonth.Holiday::name));
        return new PersonCalendar(today, month.joinedDate(), month.target().dailyMinHours(), month.leave(), holidays, weeklyOffs);
    }

    public Summary summary(AuthUser me) {
        LocalDate today = today();
        return new Summary(today, me.isOwner() || me.role() == Role.ADMIN || me.leadsTeam(), calendar(today, month(me, today)).today());
    }

    public Hours hours(AuthUser me) {
        if (!menuAccess.can(me, Menus.WORKLOG_MY)) {
            return null;
        }
        LocalDate today = today();
        WorkLogMonth month = month(me, today);
        return hours(today, month, calendar(today, month));
    }

    public Attendance attendance(AuthUser me) {
        if (!menuAccess.can(me, Menus.ATT_CHECKIN)) {
            return null;
        }
        LocalDate today = today();
        WorkLogMonth month = month(me, today);
        return attendance(me, today, month, calendar(today, month));
    }

    public Leave leave(AuthUser me) {
        return menuAccess.can(me, Menus.LEAVE_MY) ? readLeave(me) : null;
    }

    public Tasks tasks(AuthUser me) {
        return menuAccess.can(me, Menus.WORKLOG_MY) ? readTasks(me) : null;
    }

    public Pay pay(AuthUser me) {
        return menuAccess.can(me, Menus.MY_PAY) ? readPay(me) : null;
    }

    public Profile profile(AuthUser me) {
        var profile = documents.status(me, null);
        return new Profile(profile.percent(), profile.done(), profile.total());
    }

    public List<MeetingDtos.View> meetingsToday(AuthUser me) {
        LocalDate today = today();
        return meetings.mine(me, today, today).meetings();
    }

    /** The days to read: this month and this week, which can run into the next or the last month. */
    static LocalDate firstDay(LocalDate today) {
        LocalDate monday = PersonCalendar.monday(today);
        LocalDate first = YearMonth.from(today).atDay(1);
        return monday.isBefore(first) ? monday : first;
    }

    static LocalDate lastDay(LocalDate today) {
        LocalDate sunday = PersonCalendar.sunday(today);
        LocalDate last = YearMonth.from(today).atEndOfMonth();
        return sunday.isAfter(last) ? sunday : last;
    }

    private Hours hours(LocalDate today, WorkLogMonth month, PersonCalendar cal) {
        Map<LocalDate, BigDecimal> perDay = month.entries().stream()
                .collect(Collectors.toMap(e -> e.workDate(), e -> e.hours(), BigDecimal::add));
        List<DayHours> days = new ArrayList<>();
        BigDecimal week = BigDecimal.ZERO;
        for (LocalDate d = PersonCalendar.monday(today); !d.isAfter(PersonCalendar.sunday(today)); d = d.plusDays(1)) {
            BigDecimal h = perDay.getOrDefault(d, BigDecimal.ZERO);
            week = week.add(h);
            days.add(new DayHours(d, h, cal.off(d)));
        }
        WorkTarget t = month.target();
        return new Hours(perDay.getOrDefault(today, BigDecimal.ZERO), t.dailyMinHours(), week, t.weeklyMinHours(), days);
    }

    private Attendance attendance(AuthUser me, LocalDate today, WorkLogMonth month, PersonCalendar cal) {
        Map<LocalDate, AttendanceDaySummary> days = attendance.days(me, null, month.from(), month.to()).stream()
                .collect(Collectors.toMap(AttendanceDaySummary::workDate, d -> d, (a, b) -> a));
        AttendanceDaySummary now = days.get(today);
        long minutes = now == null ? 0 : minutesSoFar(now);
        boolean working = cal.workingDay(today) && !cal.wholeDayLeave(today);
        long target = working ? cal.targetMinutes() : 0;
        // a check-in on a day with no hours expected still shows as attended
        Integer percent = target > 0 ? (int) Math.min(100, minutes * 100 / target)
                : now != null ? Integer.valueOf(100) : working ? Integer.valueOf(0) : null;
        return new Attendance(now != null, working, now == null ? null : now.firstIn(), minutes, target, percent,
                cal.period(PersonCalendar.monday(today), PersonCalendar.sunday(today), days.keySet()),
                cal.period(YearMonth.from(today).atDay(1), YearMonth.from(today).atEndOfMonth(), days.keySet()));
    }

    /** The closed entries, and the one still open up to now. */
    private static long minutesSoFar(AttendanceDaySummary day) {
        long open = day.entries().stream().filter(e -> e.checkOut() == null)
                .mapToLong((AttendanceRecord e) -> Duration.between(e.checkIn().at(), Instant.now()).toMinutes()).sum();
        return day.workedMinutes() + open;
    }

    private Leave readLeave(AuthUser me) {
        LeavePlanDtos.MyCurrent plan = leave.myCurrent(me);
        BigDecimal entitled = BigDecimal.ZERO;
        BigDecimal taken = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        List<LeaveRow> rows = new ArrayList<>();
        for (LeaveBalance b : plan.balances()) {
            taken = taken.add(b.used());
            pending = pending.add(b.pending());
            if (b.entitled() != null) {
                entitled = entitled.add(b.entitled());
            }
            if (b.available() != null) {
                remaining = remaining.add(b.available());
            }
            rows.add(new LeaveRow(b.code(), b.name(), b.entitled(), b.used(), b.pending(), b.available()));
        }
        return new Leave(plan.validFrom(), plan.validUntil(), entitled, taken, pending, remaining, rows);
    }

    private Tasks readTasks(AuthUser me) {
        Set<Long> markedDone = new HashSet<>(tasks.markedCompleteBy(me.id()));
        int completed = 0;
        List<WorkTask> open = new ArrayList<>();
        for (WorkTask t : tasks.allAssignedTo(me.id())) {
            if (t.getStatus() == TaskStatus.CLOSED || markedDone.contains(t.getId())) {
                completed++;
            } else {
                open.add(t);
            }
        }
        return new Tasks(completed, open.size(), open.stream().limit(5).map(t -> new TaskRow(t.getId(), t.getCode(), t.getTitle())).toList());
    }

    private Pay readPay(AuthUser me) {
        MyPay p = payslips.myPay(me, null);
        var d = p.dashboard();
        List<MonthNet> months = p.months().stream().map(m -> new MonthNet(m.label(), m.net())).toList();
        return new Pay(p.fyLabel(), p.salary().set(), d.receivedNet(), d.expectedNet(), d.expectedNet().subtract(d.receivedNet()).max(BigDecimal.ZERO),
                d.monthsPaid(), d.monthsInYear(), months);
    }

    /** brandId: one brand's only (0 = the people without a brand); null = all I send for. */
    public List<Mail> mail(AuthUser me, Long brandId) {
        List<Mail> mail = new ArrayList<>();
        if (menuAccess.can(me, Menus.STAFF_MAIL)) {
            addMail(mail, "Staff email", staffMail.rules(me), brandId);
        }
        if (menuAccess.can(me, Menus.PROD_MAIL)) {
            addMail(mail, "Customer & supplier email", tradeMail.rules(me), brandId);
        }
        return mail;
    }

    private static void addMail(List<Mail> mail, String kind, List<MailLimitsService.BrandLimits> rows, Long brandId) {
        List<MailLimitsService.BrandLimits> shown = brandId == null ? rows
                : rows.stream().filter(r -> brandId == (r.brandId() == null ? 0L : r.brandId())).toList();
        if (!shown.isEmpty()) {
            mail.add(mailOf(kind, shown));
        }
    }

    /** A brand's own count; for people who send for every brand, all of them added up (no limit if any brand has none). */
    private static Mail mailOf(String kind, List<MailLimitsService.BrandLimits> rows) {
        int sent = rows.stream().mapToInt(MailLimitsService.BrandLimits::sentToday).sum();
        boolean limited = !rows.isEmpty() && rows.stream().allMatch(r -> r.dailyLimit() != null);
        Integer limit = limited ? Integer.valueOf(rows.stream().mapToInt(MailLimitsService.BrandLimits::dailyLimit).sum()) : null;
        Integer left = limited ? Integer.valueOf(rows.stream().mapToInt(MailLimitsService.BrandLimits::leftToday).sum()) : null;
        String scope = rows.size() == 1 ? rows.get(0).brandCode() : "All brands";
        return new Mail(kind, Objects.requireNonNullElse(scope, "Group"), sent, limit, left);
    }
}
