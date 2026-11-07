/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.AttendanceRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.LeaveRequestRepository;
import com.amsidel.backend.repository.WorkLogRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Work logging: everyone logs the open tasks assigned to them (see TaskService), day by day. Logs are seen by the person, by everyone
 * above them in the reporting line, and by owners and admins (in their brand); only the person changes them.
 * Minimum hours come from the person's own target, else the work hour rules of their brand (WorkRuleService).
 */
@Service
public class WorkLogService {

    private static final BigDecimal DAY = new BigDecimal("24");
    private static final int MAX_RANGE_DAYS = 62;
    private static final List<LeaveStatus> SHOWN_LEAVE = List.of(LeaveStatus.APPROVED, LeaveStatus.PENDING);

    private final WorkLogRepository logRepository;
    private final WorkRuleService ruleService;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRepository;
    private final TaskService taskService;
    private final TeamAccess access;
    private final OfficeHolidays officeHolidays;
    private final WorkApprovalService approvals;
    private final AttendanceRepository attendances;
    private final MenuAccessService menuAccess;
    private final ManualSalesService manualSales;
    private final Set<java.time.DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public WorkLogService(WorkLogRepository logRepository, WorkRuleService ruleService,
                          EmployeeRepository employeeRepository, LeaveRequestRepository leaveRepository,
                          TaskService taskService, TeamAccess access, OfficeHolidays officeHolidays, WorkApprovalService approvals,
                          AttendanceRepository attendances, MenuAccessService menuAccess, ManualSalesService manualSales,
                          @Value("${app.leave.weekly-offs}") Set<java.time.DayOfWeek> weeklyOffs, @Value("${app.leave.zone}") ZoneId zone) {
        this.logRepository = logRepository;
        this.ruleService = ruleService;
        this.employeeRepository = employeeRepository;
        this.leaveRepository = leaveRepository;
        this.taskService = taskService;
        this.access = access;
        this.officeHolidays = officeHolidays;
        this.approvals = approvals;
        this.attendances = attendances;
        this.menuAccess = menuAccess;
        this.manualSales = manualSales;
        this.weeklyOffs = weeklyOffs;
        this.zone = zone;
    }

    // ---- the calendar ----

    @Transactional(readOnly = true)
    public WorkLogMonth month(AuthUser me, Long employeeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Ask for at most " + MAX_RANGE_DAYS + " days at a time");
        }
        Employee person = employee(employeeId == null ? me.id() : employeeId);
        if (!canView(me, person)) {
            throw ApiException.forbidden("You can only see your own work log and those of people below you");
        }
        return monthOf(me, person, from, to);
    }

    /** The calendar data for any range; the caller checks who may see it (the export). */
    WorkLogMonth monthOf(AuthUser me, Employee person, LocalDate from, LocalDate to) {
        List<WorkLog> logs = logRepository.forEmployee(person.getId(), from, to);
        Map<Long, com.amsidel.backend.dto.ManualSalesDtos.EntryView> sales = manualSales.viewsFor(logs.stream().map(WorkLog::getId).toList());
        List<WorkLogEntry> entries = logs.stream().map(w -> WorkLogEntry.from(w, sales.get(w.getId()))).toList();
        List<WorkLogMonth.LeaveSpan> leave = leaveRepository.overlapping(person.getId(), SHOWN_LEAVE, from, to).stream()
                .map(r -> new WorkLogMonth.LeaveSpan(r.getFromDate(), r.getToDate(), r.isHalfDay(),
                        r.getLeaveType().getCode(), r.getLeaveType().getName(), r.getStatus().name()))
                .toList();
        List<WorkLogMonth.Holiday> holidays = officeHolidays
                .forBrand(person.getBrand() == null ? null : person.getBrand().getId(), from, to).entrySet().stream()
                .map(h -> new WorkLogMonth.Holiday(h.getKey(), h.getValue()))
                .toList();
        return new WorkLogMonth(person.getId(), person.getName(), person.getEmpNumber(), person.getJoinedDate(), from, to, today(),
                target(person), person.getId().equals(me.id()), entries, leave, holidays, dayAttendance(person, from, to),
                menuAccess.givenItself(com.amsidel.backend.security.AuthUser.of(person), Menus.ATT_HOURS_CHECK));
    }

    /** Checked-in time day by day; an entry still open today counts up to now, and a rejected one not at all. */
    private List<WorkLogMonth.DayAttendance> dayAttendance(Employee person, LocalDate from, LocalDate to) {
        LocalDate today = today();
        Instant now = Instant.now();
        Map<LocalDate, long[]> days = new TreeMap<>();
        for (Attendance a : attendances.forEmployee(person.getId(), from, to)) {
            if (a.getApproval() == ApprovalStatus.REJECTED) {
                continue;
            }
            long[] d = days.computeIfAbsent(a.getWorkDate(), k -> new long[4]);
            d[3] += a.getApproval() == ApprovalStatus.PENDING ? 1 : 0;
            boolean open = a.getCheckOutAt() == null;
            if (open && !a.getWorkDate().equals(today)) {
                continue;
            }
            d[a.counts() ? 0 : 1] += Duration.between(a.getCheckInAt(), open ? now : a.getCheckOutAt()).toMinutes();
            d[2] += open ? 1 : 0;
        }
        return days.entrySet().stream().map(e -> new WorkLogMonth.DayAttendance(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2] > 0, e.getValue()[3] > 0)).toList();
    }

    /** Checked-in time against the work hour rule (see AttendanceShortfall); the caller has the page's tick. */
    @Transactional(readOnly = true)
    public AttendanceShortfall shortfall(AuthUser me, Long employeeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Ask for at most " + MAX_RANGE_DAYS + " days at a time");
        }
        Employee person = employee(employeeId == null ? me.id() : employeeId);
        if (!canView(me, person)) {
            throw ApiException.forbidden("You can only see your own attendance and that of people below you");
        }
        WorkLogMonth m = monthOf(me, person, from, to);
        LocalDate today = today();
        Map<LocalDate, String> holidays = new HashMap<>();
        m.holidays().forEach(h -> holidays.put(h.date(), h.name()));
        PersonCalendar cal = new PersonCalendar(today, m.joinedDate(), m.target().dailyMinHours(), m.leave(), holidays, weeklyOffs);
        Map<LocalDate, WorkLogMonth.DayAttendance> byDay = new HashMap<>();
        m.attendance().forEach(a -> byDay.put(a.date(), a));
        List<AttendanceShortfall.Row> rows = new ArrayList<>();
        long required = 0;
        long worked = 0;
        long waiting = 0;
        long shortBy = 0;
        for (LocalDate d = from; !d.isAfter(to) && !d.isAfter(today); d = d.plusDays(1)) {
            long need = cal.requiredMinutes(d);
            WorkLogMonth.DayAttendance a = byDay.get(d);
            long done = a == null ? 0 : a.minutes();
            long wait = a == null ? 0 : a.waitingMinutes();
            boolean isToday = d.equals(today);
            if (need == 0 && done + wait == 0) {
                continue;
            }
            long missing = isToday ? 0 : Math.max(0, need - done - wait);
            rows.add(new AttendanceShortfall.Row(d, isToday, need, done, wait, missing));
            if (!isToday) {
                required += need;
                worked += done;
                waiting += wait;
                shortBy += missing;
            }
        }
        return new AttendanceShortfall(m.target().dailyMinHours(), m.target().source(), required, worked, waiting, shortBy, rows);
    }

    /** People whose work log I may look at (not me): everyone below me, plus my scope as owner or admin. */
    @Transactional(readOnly = true)
    public List<TeamMember> team(AuthUser me) {
        return access.members(me);
    }

    // ---- my own entries ----

    @Transactional
    public WorkLogEntry add(AuthUser me, WorkLogRequest request) {
        Employee person = employee(me.id());
        checkDay(person, request.getWorkDate(), request.getHours(), -1L);
        WorkLog log = new WorkLog();
        log.setEmployee(person);
        log.setWorkDate(request.getWorkDate());
        log.setSequence(logRepository.lastSequence(person.getId(), request.getWorkDate()) + 1);
        // every entry is against an open task assigned to this person
        log.setTask(taskService.loggable(person.getId(), request.getTaskId()));
        apply(log, request);
        log.setApproval(approvals.startingStatus(person));
        WorkLog saved = logRepository.save(log);
        return WorkLogEntry.from(saved, manualSales.saveFor(me, person, saved, request.getManualSales()));
    }

    @Transactional
    public WorkLogEntry update(AuthUser me, Long id, WorkLogRequest request) {
        WorkLog log = own(me, id);
        checkDay(log.getEmployee(), request.getWorkDate(), request.getHours(), log.getId());
        if (!log.getWorkDate().equals(request.getWorkDate())) {
            // moved to another day: last in that day's order
            log.setSequence(logRepository.lastSequence(log.getEmployee().getId(), request.getWorkDate()) + 1);
            log.setWorkDate(request.getWorkDate());
        }
        // a different task must be open and assigned; keeping the same one is always allowed (even if closed since)
        if (!request.getTaskId().trim().equalsIgnoreCase(log.getTaskId())) {
            log.setTask(taskService.loggable(log.getEmployee().getId(), request.getTaskId()));
        }
        apply(log, request);
        // a change goes back to the approver, whatever was decided before
        log.setApproval(approvals.startingStatus(log.getEmployee()));
        log.setDecidedBy(null);
        log.setDecidedAt(null);
        log.setDecisionNote(null);
        return WorkLogEntry.from(log, manualSales.saveFor(me, log.getEmployee(), log, request.getManualSales()));
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        WorkLog log = own(me, id);
        manualSales.removeFor(log.getId());
        logRepository.delete(log);
    }

    // ---- minimum hours ----

    /** One person's own minimum hours: owners, admins in scope, or anyone above them in the reporting line. */
    @Transactional
    public WorkTarget setTarget(AuthUser me, Long employeeId, WorkTargetRequest request) {
        Employee person = employee(employeeId);
        boolean self = person.getId().equals(me.id());
        if ((self && !me.isOwner()) || !canView(me, person)) {
            throw ApiException.forbidden("Only an owner, an admin or their manager can set this person's hours");
        }
        person.setMinDailyHours(request.getDailyMinHours());
        person.setMinWeeklyHours(request.getWeeklyMinHours());
        return target(person);
    }

    WorkTarget target(Employee person) {
        WorkTarget base = ruleService.target(person);
        boolean personal = person.getMinDailyHours() != null || person.getMinWeeklyHours() != null;
        if (!personal) {
            return base;
        }
        return new WorkTarget(
                person.getMinDailyHours() != null ? person.getMinDailyHours() : base.dailyMinHours(),
                person.getMinWeeklyHours() != null ? person.getMinWeeklyHours() : base.weeklyMinHours(),
                base.halfDayBelow(), base.fullDayBelow(), "Personal target");
    }

    // ---- rules ----

    private boolean canView(AuthUser me, Employee person) {
        return access.canView(me, person);
    }

    private void checkDay(Employee person, LocalDate day, BigDecimal hours, Long exceptId) {
        if (day.isAfter(today())) {
            throw ApiException.badRequest("You cannot log hours for a future date");
        }
        if (hours.remainder(new BigDecimal("0.25")).signum() != 0) {
            throw ApiException.badRequest("Log hours in quarters, e.g. 1.5 or 2.25");
        }
        BigDecimal total = logRepository.hoursOn(person.getId(), day, exceptId).add(hours);
        if (total.compareTo(DAY) > 0) {
            throw ApiException.badRequest("That makes " + total.stripTrailingZeros().toPlainString()
                    + " hours on " + day + "; a day has at most 24");
        }
    }

    private static void apply(WorkLog log, WorkLogRequest request) {
        log.setTaskId(log.getTask() != null ? log.getTask().getCode() : request.getTaskId().trim().toUpperCase(Locale.ROOT));
        log.setHours(request.getHours());
        log.setDescription(request.getDescription().trim());
    }

    private WorkLog own(AuthUser me, Long id) {
        WorkLog log = logRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Work log entry not found: " + id));
        if (!log.getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("You can only change your own work log");
        }
        return log;
    }

    private Employee employee(Long id) {
        return employeeRepository.findWithBrandById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }
}
