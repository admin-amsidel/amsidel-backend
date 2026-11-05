/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.DashboardDtos.*;
import com.amsidel.backend.dto.LeaveBalance;
import com.amsidel.backend.dto.MeetingDtos;
import com.amsidel.backend.dto.PayslipDtos.MyPay;
import com.amsidel.backend.dto.WorkLogMonth.LeaveSpan;
import com.amsidel.backend.dto.WorkTarget;
import com.amsidel.backend.entity.Attendance;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.LeaveRequest;
import com.amsidel.backend.entity.LeaveStatus;
import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.entity.WorkTask;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.AttendanceRepository;
import com.amsidel.backend.repository.LeaveRequestRepository;
import com.amsidel.backend.repository.WorkLogRepository;
import com.amsidel.backend.repository.WorkTaskRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * The Overview's Team view: the same figures as Me, added up for everyone below the person in the reporting line and in
 * their brand (TeamAccess also gives admins their scope). Owners and people without a brand see every brand they reach,
 * and can take one brand or compare them. Each part is asked for on its own, so one slow part keeps no other waiting, and
 * reads only what it needs. A part follows the team page's tick and is null without it: Team attendance, Approvals (leave),
 * Team pay; hours and tasks My work log, which already shows managers their team's.
 */
@Service
public class TeamDashboardService {

    /** The brand key of the people without a brand. */
    static final long NO_BRAND = 0L;
    private static final String GROUP = "Group (no brand)";
    private static final Period NO_DAYS = new Period(0, 0, BigDecimal.ZERO);

    /** What a part of the page needs read. */
    private enum Need { HOURS, ATTENDANCE, LEAVE, TASKS, PROFILE }

    private final TeamAccess teamAccess;
    private final WorkLogService workLog;
    private final WorkLogRepository workLogs;
    private final AttendanceRepository attendances;
    private final LeaveRequestRepository leaveRequests;
    private final LeaveService leave;
    private final WorkTaskRepository tasks;
    private final PayslipService payslips;
    private final OfficeHolidays officeHolidays;
    private final EmployeeDocumentService documents;
    private final MeetingService meetings;
    private final DashboardService dashboard;
    private final MenuAccessService menuAccess;
    private final Set<DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public TeamDashboardService(TeamAccess teamAccess, WorkLogService workLog, WorkLogRepository workLogs, AttendanceRepository attendances,
                                LeaveRequestRepository leaveRequests, LeaveService leave, WorkTaskRepository tasks, PayslipService payslips,
                                OfficeHolidays officeHolidays, EmployeeDocumentService documents, MeetingService meetings, DashboardService dashboard,
                                MenuAccessService menuAccess, @Value("${app.leave.weekly-offs}") Set<DayOfWeek> weeklyOffs,
                                @Value("${app.leave.zone}") ZoneId zone) {
        this.teamAccess = teamAccess;
        this.workLog = workLog;
        this.workLogs = workLogs;
        this.attendances = attendances;
        this.leaveRequests = leaveRequests;
        this.leave = leave;
        this.tasks = tasks;
        this.payslips = payslips;
        this.officeHolidays = officeHolidays;
        this.documents = documents;
        this.meetings = meetings;
        this.dashboard = dashboard;
        this.menuAccess = menuAccess;
        this.weeklyOffs = weeklyOffs;
        this.zone = zone;
    }

    /** May this person take one brand or compare them? Everyone else's team is already one brand. */
    private static boolean canSplit(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }

    /** Active people below me in my brand, or every brand I reach; brandId (0 = no brand) narrows it for those who may. */
    private List<Employee> people(AuthUser me, Long brandId) {
        if (brandId != null && !canSplit(me)) {
            throw ApiException.forbidden("You can only see your own brand's team");
        }
        return teamAccess.team(me).stream()
                .filter(e -> canSplit(me) || Objects.equals(me.brandId(), brandIdOf(e)))
                .filter(e -> brandId == null || brandId == brandKey(e))
                .toList();
    }

    /** The people of my team, for the team's meetings. */
    @Transactional(readOnly = true)
    public List<Long> memberIds(AuthUser me, Long brandId) {
        return people(me, brandId).stream().map(Employee::getId).toList();
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    // ---- the parts of the page ----

    @Transactional(readOnly = true)
    public TeamSummary summary(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        // the brands to choose from stay all of them when one is chosen
        List<Employee> everyone = brandId == null ? team : people(me, null);
        return new TeamSummary(today(), team.size(), canSplit(me), brandId, canSplit(me) ? options(everyone) : List.of());
    }

    @Transactional(readOnly = true)
    public Hours hours(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        if (!menuAccess.can(me, Menus.WORKLOG_MY)) {
            return null;
        }
        LocalDate today = today();
        Reading r = read(me, team, today, EnumSet.of(Need.HOURS));
        List<DayHours> days = new ArrayList<>();
        BigDecimal week = BigDecimal.ZERO;
        for (LocalDate d = PersonCalendar.monday(today); !d.isAfter(PersonCalendar.sunday(today)); d = d.plusDays(1)) {
            BigDecimal h = r.perDay.getOrDefault(d, BigDecimal.ZERO);
            week = week.add(h);
            days.add(new DayHours(d, h, false));
        }
        return new Hours(r.all.hoursToday, r.all.dailyTarget, week, r.all.weeklyTarget, days);
    }

    @Transactional(readOnly = true)
    public TeamAttendance attendance(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        if (!menuAccess.can(me, Menus.ATT_TEAM)) {
            return null;
        }
        Totals all = read(me, team, today(), EnumSet.of(Need.ATTENDANCE)).all;
        return new TeamAttendance(all.present, all.expected, all.expected > 0 ? Integer.valueOf(all.present * 100 / all.expected) : null,
                team.size() - all.expected, all.week(), all.month());
    }

    @Transactional(readOnly = true)
    public Leave leave(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        return menuAccess.can(me, Menus.LEAVE_APPROVALS) ? read(me, team, today(), EnumSet.of(Need.LEAVE)).all.leave() : null;
    }

    @Transactional(readOnly = true)
    public Tasks tasks(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        if (!menuAccess.can(me, Menus.WORKLOG_MY)) {
            return null;
        }
        Reading r = read(me, team, today(), EnumSet.of(Need.TASKS));
        return new Tasks(r.all.tasksDone, r.all.tasksPending, r.open);
    }

    /**
     * The team's pay, and each person's and brand's share for the tables. The slowest part: it is read person by person,
     * since a salary's figures come from its own rules, so it comes apart from everything else.
     */
    @Transactional(readOnly = true)
    public TeamPay pay(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        if (!menuAccess.can(me, Menus.TEAM_PAY)) {
            return null;
        }
        boolean set = false;
        String fyLabel = null;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        int payslipCount = 0;
        int months = 0;
        Map<String, BigDecimal> perMonth = new LinkedHashMap<>();
        Map<Long, BigDecimal> byMember = new HashMap<>();
        Map<Long, BigDecimal> byBrand = new HashMap<>();
        for (Employee e : team) {
            MyPay p = payslips.teamPay(me, e.getId(), null);
            var d = p.dashboard();
            fyLabel = p.fyLabel();
            set |= p.salary().set();
            received = received.add(d.receivedNet());
            expected = expected.add(d.expectedNet());
            remaining = remaining.add(d.expectedNet().subtract(d.receivedNet()).max(BigDecimal.ZERO));
            payslipCount += d.monthsPaid();
            months += d.monthsInYear();
            p.months().forEach(m -> perMonth.merge(m.label(), orZero(m.net()), BigDecimal::add));
            byMember.put(e.getId(), d.receivedNet());
            byBrand.merge(brandKey(e), d.receivedNet(), BigDecimal::add);
        }
        List<MonthNet> monthly = perMonth.entrySet().stream()
                .map(m -> new MonthNet(m.getKey(), m.getValue().signum() == 0 ? null : m.getValue())).toList();
        Pay pay = team.isEmpty() ? null : new Pay(fyLabel, set, received, expected, remaining, payslipCount, months, monthly);
        return new TeamPay(pay, byMember, byBrand);
    }

    public List<Mail> mail(AuthUser me, Long brandId) {
        if (brandId != null && !canSplit(me)) {
            throw ApiException.forbidden("You can only see your own brand's team");
        }
        return dashboard.mail(me, brandId);
    }

    @Transactional(readOnly = true)
    public TeamProfile profile(AuthUser me, Long brandId) {
        List<Employee> team = people(me, brandId);
        Totals all = read(me, team, today(), EnumSet.of(Need.PROFILE)).all;
        return new TeamProfile(team.isEmpty() ? 0 : all.profileSum / team.size(), all.profilesComplete, team.size());
    }

    @Transactional(readOnly = true)
    public List<MeetingDtos.View> meetingsToday(AuthUser me, Long brandId) {
        LocalDate today = today();
        return meetings.ofPeople(me, memberIds(me, brandId), today, today).meetings();
    }

    /** Person by person and brand by brand, without pay (TeamPay fills that in when it comes). */
    @Transactional(readOnly = true)
    public TeamPeople teamPeople(AuthUser me, Long brandId) {
        Reading r = read(me, people(me, brandId), today(), EnumSet.allOf(Need.class));
        List<Member> members = new ArrayList<>(r.members);
        members.sort(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER));
        boolean hours = menuAccess.can(me, Menus.WORKLOG_MY);
        boolean attendance = menuAccess.can(me, Menus.ATT_TEAM);
        boolean leaves = menuAccess.can(me, Menus.LEAVE_APPROVALS);
        List<BrandRow> rows = canSplit(me) && brandId == null
                ? r.byBrand.entrySet().stream().map(x -> x.getValue().row(x.getKey(), hours, attendance, leaves))
                        .sorted(Comparator.comparing(BrandRow::name, String.CASE_INSENSITIVE_ORDER)).toList()
                : List.of();
        return new TeamPeople(members, rows);
    }

    // ---- reading for a whole team at once, then sharing it out per person ----

    /** What was read, added up. */
    private static final class Reading {
        final Totals all = new Totals(null);
        final Map<Long, Totals> byBrand = new LinkedHashMap<>();
        final Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        final List<Member> members = new ArrayList<>();
        List<TaskRow> open = List.of();
    }

    private Reading read(AuthUser me, List<Employee> team, LocalDate today, Set<Need> need) {
        boolean showHours = need.contains(Need.HOURS) && menuAccess.can(me, Menus.WORKLOG_MY);
        boolean showTasks = need.contains(Need.TASKS) && menuAccess.can(me, Menus.WORKLOG_MY);
        boolean showAttendance = need.contains(Need.ATTENDANCE) && menuAccess.can(me, Menus.ATT_TEAM);
        boolean showLeave = need.contains(Need.LEAVE) && menuAccess.can(me, Menus.LEAVE_APPROVALS);
        boolean showProfile = need.contains(Need.PROFILE);

        List<Long> ids = team.stream().map(Employee::getId).toList();
        LocalDate from = DashboardService.firstDay(today);
        LocalDate to = DashboardService.lastDay(today);
        LocalDate monday = PersonCalendar.monday(today);
        LocalDate sunday = PersonCalendar.sunday(today);
        LocalDate first = YearMonth.from(today).atDay(1);
        LocalDate last = YearMonth.from(today).atEndOfMonth();

        Map<Long, Map<LocalDate, BigDecimal>> hoursOf = showHours ? hours(ids, from, to) : Map.of();
        Map<Long, Map<LocalDate, List<Attendance>>> daysOf = showAttendance ? attendance(ids, from, to) : Map.of();
        Map<Long, List<LeaveSpan>> leaveOf = showAttendance ? approvedLeave(ids, from, to) : Map.of();
        Map<Long, List<LeaveBalance>> balancesOf = showLeave ? leave.balancesOfTeam(team) : Map.of();
        Map<Long, int[]> tasksOf = new HashMap<>();
        Reading out = new Reading();
        if (showTasks) {
            out.open = tasks(ids, tasksOf);
        }
        Map<Long, Integer> profileOf = showProfile ? documents.percents(team) : Map.of();
        Map<String, WorkTarget> targets = new HashMap<>();
        Map<Long, Map<LocalDate, String>> holidays = new HashMap<>();
        Instant now = Instant.now();

        for (Employee e : team) {
            Long id = e.getId();
            WorkTarget target = showHours || showAttendance ? targets.computeIfAbsent(targetKey(e), k -> workLog.target(e)) : null;

            Map<LocalDate, BigDecimal> logged = hoursOf.getOrDefault(id, Map.of());
            logged.forEach((d, h) -> {
                if (!d.isBefore(monday) && !d.isAfter(sunday)) {
                    out.perDay.merge(d, h, BigDecimal::add);
                }
            });
            BigDecimal hoursToday = logged.getOrDefault(today, BigDecimal.ZERO);

            boolean checkedIn = false;
            boolean expected = false;
            long minutes = 0;
            String status = null;
            Period week = NO_DAYS;
            Period month = NO_DAYS;
            if (showAttendance) {
                Map<LocalDate, String> closed = holidays.computeIfAbsent(brandKey(e), k -> officeHolidays.forBrand(brandIdOf(e), from, to));
                PersonCalendar cal = new PersonCalendar(today, e.getJoinedDate(), target.dailyMinHours(), leaveOf.getOrDefault(id, List.of()), closed, weeklyOffs);
                Map<LocalDate, List<Attendance>> days = daysOf.getOrDefault(id, Map.of());
                checkedIn = days.containsKey(today);
                expected = cal.workingDay(today) && !cal.wholeDayLeave(today);
                minutes = days.getOrDefault(today, List.of()).stream().mapToLong(a -> minutesOf(a, now)).sum();
                status = checkedIn ? "CHECKED_IN" : cal.wholeDayLeave(today) ? "ON_LEAVE" : expected ? "NOT_IN" : "OFF";
                week = cal.period(monday, sunday, days.keySet());
                month = cal.period(first, last, days.keySet());
            }

            List<LeaveBalance> balances = balancesOf.getOrDefault(id, List.of());
            BigDecimal leaveTaken = balances.stream().map(LeaveBalance::used).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal leaveLeft = balances.stream().map(LeaveBalance::available).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            int[] taskCount = tasksOf.getOrDefault(id, new int[2]);
            int profile = profileOf.getOrDefault(id, 0);

            out.members.add(new Member(id, e.getName(), e.getDesignation() == null ? null : e.getDesignation().getName(),
                    e.getBrand() == null ? null : e.getBrand().getCode(), e.getPhotoPath(), e.getPhotoUpdatedAt(),
                    showHours ? hoursToday : null, status, showAttendance ? minutes : null,
                    showTasks ? taskCount[0] : null, showTasks ? taskCount[1] : null,
                    showLeave ? leaveTaken : null, showLeave ? leaveLeft : null, profile));

            Totals brand = out.byBrand.computeIfAbsent(brandKey(e), k -> new Totals(e.getBrand()));
            for (Totals t : List.of(out.all, brand)) {
                t.add(target, hoursToday, checkedIn, expected, week, month, balances, taskCount, profile);
            }
        }
        return out;
    }

    private static List<BrandOption> options(List<Employee> everyone) {
        Map<Long, BrandOption> seen = new HashMap<>();
        for (Employee e : everyone) {
            Brand b = e.getBrand();
            seen.putIfAbsent(brandKey(e), b == null ? new BrandOption(NO_BRAND, null, GROUP) : new BrandOption(b.getId(), b.getCode(), b.getName()));
        }
        return seen.values().stream().sorted(Comparator.comparing(BrandOption::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private Map<Long, Map<LocalDate, BigDecimal>> hours(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, BigDecimal>> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] r : workLogs.hoursPerDay(ids, from, to)) {
                out.computeIfAbsent((Long) r[0], k -> new HashMap<>()).put((LocalDate) r[1], (BigDecimal) r[2]);
            }
        }
        return out;
    }

    private Map<Long, Map<LocalDate, List<Attendance>>> attendance(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, List<Attendance>>> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Attendance a : attendances.ofPeople(ids, from, to)) {
                out.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>()).computeIfAbsent(a.getWorkDate(), k -> new ArrayList<>()).add(a);
            }
        }
        return out;
    }

    private static long minutesOf(Attendance a, Instant now) {
        return Duration.between(a.getCheckInAt(), a.getCheckOutAt() == null ? now : a.getCheckOutAt()).toMinutes();
    }

    private Map<Long, List<LeaveSpan>> approvedLeave(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, List<LeaveSpan>> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (LeaveRequest r : leaveRequests.overlappingOfPeople(ids, LeaveStatus.APPROVED, from, to)) {
                out.computeIfAbsent(r.getEmployee().getId(), k -> new ArrayList<>()).add(new LeaveSpan(r.getFromDate(), r.getToDate(), r.isHalfDay(),
                        r.getLeaveType().getCode(), r.getLeaveType().getName(), r.getStatus().name()));
            }
        }
        return out;
    }

    /** Fills each person's {completed, pending}; a task shared by two counts for both. Answers the open tasks, each once. */
    private List<TaskRow> tasks(List<Long> ids, Map<Long, int[]> counts) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<String> marked = new HashSet<>();
        for (Object[] r : tasks.markedCompleteByPeople(ids)) {
            marked.add(r[0] + ":" + r[1]);
        }
        Map<Long, TaskRow> open = new LinkedHashMap<>();
        for (Object[] r : tasks.assignedToPeople(ids)) {
            Long person = (Long) r[0];
            WorkTask t = (WorkTask) r[1];
            boolean done = t.getStatus() == TaskStatus.CLOSED || marked.contains(person + ":" + t.getId());
            counts.computeIfAbsent(person, k -> new int[2])[done ? 0 : 1]++;
            if (!done) {
                open.putIfAbsent(t.getId(), new TaskRow(t.getId(), t.getCode(), t.getTitle()));
            }
        }
        return open.values().stream().limit(5).toList();
    }

    private static Long brandIdOf(Employee e) {
        return e.getBrand() == null ? null : e.getBrand().getId();
    }

    private static long brandKey(Employee e) {
        return e.getBrand() == null ? NO_BRAND : e.getBrand().getId();
    }

    /** People with the same brand, level and no target of their own have the same rule. */
    private static String targetKey(Employee e) {
        return e.getMinDailyHours() != null || e.getMinWeeklyHours() != null ? "person:" + e.getId()
                : brandKey(e) + ":" + (e.getDesignation() == null ? "-" : e.getDesignation().getId());
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** The figures added up, for the team and for one brand of it. */
    private static final class Totals {
        final Brand brand;
        int people;
        BigDecimal hoursToday = BigDecimal.ZERO;
        BigDecimal dailyTarget = BigDecimal.ZERO;
        BigDecimal weeklyTarget = BigDecimal.ZERO;
        int present;
        int expected;
        int weekDays;
        int weekAttended;
        BigDecimal weekLeave = BigDecimal.ZERO;
        int monthDays;
        int monthAttended;
        BigDecimal monthLeave = BigDecimal.ZERO;
        BigDecimal entitled = BigDecimal.ZERO;
        BigDecimal taken = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        final Map<String, LeaveRow> types = new LinkedHashMap<>();
        int tasksDone;
        int tasksPending;
        int profileSum;
        int profilesComplete;

        Totals(Brand brand) {
            this.brand = brand;
        }

        void add(WorkTarget target, BigDecimal hours, boolean checkedIn, boolean isExpected, Period week, Period month,
                 List<LeaveBalance> balances, int[] taskCount, int profile) {
            people++;
            hoursToday = hoursToday.add(hours);
            if (target != null) {
                dailyTarget = dailyTarget.add(orZero(target.dailyMinHours()));
                weeklyTarget = weeklyTarget.add(orZero(target.weeklyMinHours()));
            }
            if (isExpected) {
                expected++;
                if (checkedIn) {
                    present++;
                }
            }
            weekDays += week.days();
            weekAttended += week.attended();
            weekLeave = weekLeave.add(week.leave());
            monthDays += month.days();
            monthAttended += month.attended();
            monthLeave = monthLeave.add(month.leave());
            for (LeaveBalance b : balances) {
                taken = taken.add(b.used());
                pending = pending.add(b.pending());
                entitled = entitled.add(orZero(b.entitled()));
                remaining = remaining.add(orZero(b.available()));
                types.merge(b.code(), new LeaveRow(b.code(), b.name(), b.entitled(), b.used(), b.pending(), b.available()), Totals::sum);
            }
            tasksDone += taskCount[0];
            tasksPending += taskCount[1];
            profileSum += profile;
            if (profile == 100) {
                profilesComplete++;
            }
        }

        private static LeaveRow sum(LeaveRow a, LeaveRow b) {
            return new LeaveRow(a.code(), a.name(), a.entitled() == null ? null : a.entitled().add(orZero(b.entitled())), a.used().add(b.used()),
                    a.pending().add(b.pending()), a.available() == null ? null : a.available().add(orZero(b.available())));
        }

        Period week() {
            return new Period(weekDays, weekAttended, weekLeave);
        }

        Period month() {
            return new Period(monthDays, monthAttended, monthLeave);
        }

        Leave leave() {
            return new Leave(null, null, entitled, taken, pending, remaining, List.copyOf(types.values()));
        }

        BrandRow row(long key, boolean hours, boolean attendance, boolean leave) {
            return new BrandRow(key, brand == null ? null : brand.getCode(), brand == null ? GROUP : brand.getName(), people,
                    hours ? hoursToday : null, attendance ? present : null, attendance ? expected : null,
                    hours ? tasksDone : null, hours ? tasksPending : null, leave ? taken : null, leave ? remaining : null,
                    profileSum / Math.max(1, people));
        }
    }
}
