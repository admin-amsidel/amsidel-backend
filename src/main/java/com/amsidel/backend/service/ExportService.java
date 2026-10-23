package com.amsidel.backend.service;

import com.amsidel.backend.dto.AttendanceDaySummary;
import com.amsidel.backend.dto.AttendanceRecord;
import com.amsidel.backend.dto.WorkLogEntry;
import com.amsidel.backend.dto.WorkLogMonth;
import com.amsidel.backend.dto.WorkTarget;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.WorkMode;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attendance and work log as Excel files, for one person or for me and everyone I may look at, over a date range
 * of up to a year. Each file has a summary per person, every day of the range with what it was (present, weekly
 * off, holiday, leave, absent…) and the entries themselves. Who may be exported follows the same rule as the
 * screens: myself and the people below me (owners and admins: their scope).
 */
@Service
public class ExportService {

    /** A year, so a financial or calendar year fits in one file. */
    static final int MAX_DAYS = 366;
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    /** A file and its name. */
    public record Export(String fileName, byte[] bytes) {
    }

    private final EmployeeRepository employeeRepository;
    private final TeamAccess access;
    private final AttendanceService attendanceService;
    private final WorkLogService workLogService;
    private final Set<DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public ExportService(EmployeeRepository employeeRepository, TeamAccess access, AttendanceService attendanceService,
                         WorkLogService workLogService, @Value("${app.leave.weekly-offs:SUNDAY}") Set<DayOfWeek> weeklyOffs,
                         @Value("${app.attendance.zone}") ZoneId zone) {
        this.employeeRepository = employeeRepository;
        this.access = access;
        this.attendanceService = attendanceService;
        this.workLogService = workLogService;
        this.weeklyOffs = weeklyOffs;
        this.zone = zone;
    }

    // ---- attendance ----

    @Transactional(readOnly = true)
    public Export attendance(AuthUser me, Long employeeId, boolean all, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<Employee> people = people(me, employeeId, all);
        LocalDate today = LocalDate.now(zone);
        String range = LABEL.format(from) + " to " + LABEL.format(to);
        String note = note(me, people, today);
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table summary = book.sheet("Summary", "Attendance summary, " + range, note,
                    new String[]{"Emp no", "Name", "Brand", "Designation", "Working days", "Present days", "Office days",
                            "Work from home days", "Field visits", "Hours worked", "Average hours a present day",
                            "Leave days (approved)", "Absent days", "Days with location issues"},
                    new int[]{10, 24, 18, 22, 10, 10, 10, 12, 10, 10, 12, 12, 10, 12});
            ExcelBook.Table daily = book.sheet("Daily", "Attendance day by day, " + range,
                    "Every day of the range. Absent = a past working day with no check-in and no approved leave.",
                    new String[]{"Emp no", "Name", "Date", "Day", "Status", "First check-in", "Last check-out",
                            "Hours worked", "Work modes", "Entries", "Location issues", "Leave / holiday"},
                    new int[]{10, 24, 13, 6, 22, 12, 12, 10, 18, 8, 22, -30});
            ExcelBook.Table entries = book.sheet("Entries", "Attendance entries, " + range,
                    "Each check-in and check-out; a field day has one entry per visit. Times are " + zone.getId() + ".",
                    new String[]{"Emp no", "Name", "Date", "Entry", "Work mode", "Place / client", "Check-in", "Check-out",
                            "Hours", "Note at check-out", "Check-in location", "Check-in accuracy (m)",
                            "Check-out location", "Location issues", "Device", "Check-in IP"},
                    new int[]{10, 24, 13, 7, 14, -24, 12, 12, 8, -40, 22, 12, 22, 22, -22, 16});
            double sumHours = 0;
            int sumPresent = 0;
            for (Employee e : people) {
                WorkLogMonth cal = workLogService.monthOf(me, e, from, to);
                List<AttendanceDaySummary> found = attendanceService.daysOf(e, from, to);
                Map<LocalDate, AttendanceDaySummary> days = found.stream()
                        .collect(Collectors.toMap(AttendanceDaySummary::workDate, Function.identity()));
                int working = 0, present = 0, office = 0, wfh = 0, field = 0, absent = 0, flagged = 0;
                double leaveDays = 0;
                long minutes = 0;
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    Day day = day(d, cal, today);
                    AttendanceDaySummary a = days.get(d);
                    if (day.working() && !day.beforeJoining() && !d.isAfter(today)) {
                        working++;
                        if (day.leave() != null && day.leaveApproved()) {
                            leaveDays += day.leaveHalf() ? 0.5 : 1;
                        }
                    }
                    String status;
                    if (a != null) {
                        present++;
                        minutes += a.workedMinutes();
                        office += a.modes().contains(WorkMode.OFFICE) ? 1 : 0;
                        wfh += a.modes().contains(WorkMode.WFH) ? 1 : 0;
                        field += (int) a.entries().stream().filter(x -> x.workMode() == WorkMode.FIELD).count();
                        flagged += a.flags().isEmpty() ? 0 : 1;
                        status = a.open() ? "Present (not checked out)" : "Present";
                        if (day.leave() != null) {
                            status += day.leaveHalf() ? " · half-day leave" : " · on leave";
                        } else if (!day.working() && day.label() != null) {
                            status += " · " + day.label().toLowerCase(Locale.ENGLISH);
                        }
                    } else if (day.absent()) {
                        absent++;
                        status = "Absent";
                    } else {
                        status = day.label();
                    }
                    daily.row(e.getEmpNumber(), e.getName(), d, dow(d), status,
                            a == null ? null : time(a.firstIn()), a == null ? null : time(a.lastOut()),
                            a == null ? null : ExcelBook.Hours.ofMinutes(a.workedMinutes()),
                            a == null ? null : a.modes().stream().map(ExportService::mode).collect(Collectors.joining(", ")),
                            a == null ? null : a.entries().size(),
                            a == null || a.flags().isEmpty() ? null : a.flags().stream().map(ExportService::flag).collect(Collectors.joining(", ")),
                            day.detail());
                }
                // entries, oldest first
                found.stream()
                        .sorted(Comparator.comparing(AttendanceDaySummary::workDate))
                        .flatMap(a -> a.entries().stream())
                        .forEach(x -> entries.row(e.getEmpNumber(), e.getName(), x.workDate(), x.entryNo(), mode(x.workMode()),
                                x.place(), time(x.checkIn().at()), x.checkOut() == null ? null : time(x.checkOut().at()),
                                x.workedMinutes() == null ? null : ExcelBook.Hours.ofMinutes(x.workedMinutes()), x.note(),
                                where(x.checkIn()), x.checkIn().accuracy() == null ? null : Math.round(x.checkIn().accuracy()),
                                x.checkOut() == null ? null : where(x.checkOut()), flags(x), device(x), x.checkIn().ip()));
                double hours = Math.round(minutes / 60.0 * 100) / 100.0;
                summary.row(e.getEmpNumber(), e.getName(), brand(e), designation(e), working, present, office, wfh, field,
                        new ExcelBook.Hours(hours), present == 0 ? null : new ExcelBook.Hours(Math.round(hours / present * 100) / 100.0),
                        new ExcelBook.Hours(leaveDays), absent, flagged);
                sumHours += hours;
                sumPresent += present;
            }
            if (people.size() > 1) {
                summary.totals("", "All " + people.size() + " people", null, null, null, sumPresent, null, null, null,
                        new ExcelBook.Hours(sumHours));
            } else {
                summary.done();
            }
            daily.done();
            entries.done();
            return new Export(fileName("Attendance", people, all, from, to), book.bytes());
        }
    }

    // ---- work log ----

    @Transactional(readOnly = true)
    public Export workLog(AuthUser me, Long employeeId, boolean all, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<Employee> people = people(me, employeeId, all);
        LocalDate today = LocalDate.now(zone);
        String range = LABEL.format(from) + " to " + LABEL.format(to);
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table summary = book.sheet("Summary", "Work log summary, " + range, note(me, people, today),
                    new String[]{"Emp no", "Name", "Brand", "Designation", "Daily minimum", "Weekly minimum", "Minimum from",
                            "Working days", "Days logged", "Hours logged", "Hours needed", "Short by",
                            "Half Day Leave days", "Full Day Leave days", "Leave days (approved)"},
                    new int[]{10, 24, 18, 22, 10, 10, 18, 10, 10, 10, 10, 10, 12, 12, 12});
            ExcelBook.Table daily = book.sheet("Daily", "Work log day by day, " + range,
                    "Hours needed = the daily minimum, less approved leave; holidays and weekly offs need none. "
                            + "Half / Full Day Leave: a past working day logged below the person's limits.",
                    new String[]{"Emp no", "Name", "Date", "Day", "Hours logged", "Hours needed", "Short by", "Entries",
                            "Status", "Leave / holiday"},
                    new int[]{10, 24, 13, 6, 10, 10, 10, 8, 20, -30});
            ExcelBook.Table entries = book.sheet("Entries", "Work log entries, " + range, "Each logged piece of work.",
                    new String[]{"Emp no", "Name", "Date", "Day", "No", "Task ID", "Task", "Hours", "What was done",
                            "Logged at", "Last changed"},
                    new int[]{10, 24, 13, 6, 5, 12, -30, 8, -60, 18, 18});
            double sumLogged = 0, sumNeeded = 0;
            for (Employee e : people) {
                WorkLogMonth cal = workLogService.monthOf(me, e, from, to);
                WorkTarget t = cal.target();
                double dailyMin = t.dailyMinHours().doubleValue();
                Map<LocalDate, List<WorkLogEntry>> byDay = cal.entries().stream()
                        .collect(Collectors.groupingBy(WorkLogEntry::workDate));
                int working = 0, logged = 0, half = 0, full = 0;
                double hours = 0, needed = 0, leaveDays = 0;
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    Day day = day(d, cal, today);
                    List<WorkLogEntry> list = byDay.getOrDefault(d, List.of());
                    double total = round(list.stream().mapToDouble(x -> x.hours().doubleValue()).sum());
                    // an approved leave or a holiday lowers what the day needs, as on the calendar
                    double off = !day.working() ? dailyMin : day.leaveApproved() ? (day.leaveHalf() ? dailyMin / 2 : dailyMin) : 0;
                    double required = day.closed() || day.beforeJoining() ? 0 : Math.max(0, dailyMin - off);
                    boolean past = d.isBefore(today);
                    String status = day.label();
                    if (day.working() && !day.beforeJoining()) {
                        if (!d.isAfter(today)) {
                            working++;
                        }
                        if (day.leaveApproved()) {
                            leaveDays += day.leaveHalf() ? 0.5 : 1;
                        }
                        if (past && !day.leaveApproved()) {
                            if (total < t.fullDayBelow().doubleValue()) {
                                status = "Full Day Leave";
                                full++;
                            } else if (total < t.halfDayBelow().doubleValue()) {
                                status = "Half Day Leave";
                                half++;
                            } else {
                                status = total >= required ? "Met the minimum" : "Below the minimum";
                            }
                        } else if (!past && status == null) {
                            status = d.isEqual(today) ? "Today" : "Upcoming";
                        }
                    }
                    if (!list.isEmpty()) {
                        logged++;
                    }
                    hours += total;
                    double need = d.isAfter(today) ? 0 : required;
                    needed += need;
                    daily.row(e.getEmpNumber(), e.getName(), d, dow(d), new ExcelBook.Hours(total),
                            new ExcelBook.Hours(round(required)),
                            d.isAfter(today) ? null : new ExcelBook.Hours(round(Math.max(0, required - total))),
                            list.size(), status, day.detail());
                    for (WorkLogEntry x : list.stream().sorted(Comparator.comparingInt(WorkLogEntry::sequence)).toList()) {
                        entries.row(e.getEmpNumber(), e.getName(), d, dow(d), x.sequence(), x.taskId(), x.taskTitle(),
                                ExcelBook.Hours.of(x.hours()), x.description(), local(x.createdAt()), local(x.updatedAt()));
                    }
                }
                summary.row(e.getEmpNumber(), e.getName(), brand(e), designation(e), ExcelBook.Hours.of(t.dailyMinHours()),
                        ExcelBook.Hours.of(t.weeklyMinHours()), t.source(), working, logged, new ExcelBook.Hours(round(hours)),
                        new ExcelBook.Hours(round(needed)), new ExcelBook.Hours(round(Math.max(0, needed - hours))), half, full,
                        new ExcelBook.Hours(leaveDays));
                sumLogged += hours;
                sumNeeded += needed;
            }
            if (people.size() > 1) {
                summary.totals("", "All " + people.size() + " people", null, null, null, null, null, null, null,
                        new ExcelBook.Hours(round(sumLogged)), new ExcelBook.Hours(round(sumNeeded)));
            } else {
                summary.done();
            }
            daily.done();
            entries.done();
            return new Export(fileName("WorkLog", people, all, from, to), book.bytes());
        }
    }

    // ---- what a day was ----

    /**
     * label: what the day is when nobody worked (Weekly off, Holiday, On leave…); detail: the holiday or leave.
     * working = not a weekly off or holiday; absent = a past working day without approved leave (no check-in is
     * checked by the caller).
     */
    private record Day(boolean closed, boolean working, boolean beforeJoining, String label, String detail,
                       WorkLogMonth.LeaveSpan leave, boolean leaveApproved, boolean leaveHalf, boolean absent) {
    }

    private Day day(LocalDate d, WorkLogMonth cal, LocalDate today) {
        boolean weeklyOff = weeklyOffs.contains(d.getDayOfWeek());
        String holiday = weeklyOff ? null : cal.holidays().stream().filter(h -> h.date().equals(d))
                .map(WorkLogMonth.Holiday::name).findFirst().orElse(null);
        boolean closed = weeklyOff || holiday != null;
        boolean beforeJoining = cal.joinedDate() != null && d.isBefore(cal.joinedDate());
        WorkLogMonth.LeaveSpan leave = closed ? null : cal.leave().stream()
                .filter(l -> !d.isBefore(l.fromDate()) && !d.isAfter(l.toDate())).findFirst().orElse(null);
        boolean approved = leave != null && "APPROVED".equals(leave.status());
        boolean half = leave != null && leave.halfDay();
        String label;
        String detail = null;
        if (weeklyOff) {
            label = "Weekly off";
        } else if (holiday != null) {
            label = "Holiday";
            detail = holiday;
        } else if (beforeJoining) {
            label = "Before joining";
        } else if (leave != null) {
            label = approved ? (half ? "Half-day leave" : "On leave") : "Leave requested";
            detail = leave.typeName() + (half ? " (half day)" : "") + (approved ? "" : ", waiting for approval");
        } else if (d.isAfter(today)) {
            label = "Upcoming";
        } else if (d.isEqual(today)) {
            label = "Today";
        } else {
            label = null;
        }
        // a past working day without approved leave; whether there was a check-in is up to the caller
        boolean absent = !closed && !beforeJoining && d.isBefore(today) && !approved;
        return new Day(closed, !closed, beforeJoining, label, detail, leave, approved, half, absent);
    }

    // ---- who and when ----

    /** Me and everyone I may look at (all), the one person asked for, or me. */
    private List<Employee> people(AuthUser me, Long employeeId, boolean all) {
        Employee self = employeeRepository.findWithBrandById(me.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id()));
        if (all) {
            List<Employee> list = new ArrayList<>();
            list.add(self);
            access.team(me).stream().sorted(Comparator.comparing(Employee::getName, String.CASE_INSENSITIVE_ORDER)).forEach(list::add);
            return list;
        }
        if (employeeId == null || employeeId.equals(me.id())) {
            return List.of(self);
        }
        Employee person = employeeRepository.findWithBrandById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        if (!access.canView(me, person)) {
            throw ApiException.forbidden("You can only export your own records and those of people below you");
        }
        return List.of(person);
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("Export at most " + MAX_DAYS + " days (a year) at a time");
        }
    }

    private String note(AuthUser me, List<Employee> people, LocalDate today) {
        String who = people.size() == 1 ? people.getFirst().getName() + " (" + people.getFirst().getEmpNumber() + ")"
                : people.size() + " people";
        String by = people.stream().filter(e -> e.getId().equals(me.id())).map(Employee::getName).findFirst()
                .orElseGet(() -> employeeRepository.findById(me.id()).map(Employee::getName).orElse(me.userId()));
        return who + " · exported by " + by + " on " + STAMP.format(LocalDateTime.now(zone)) + " · Amsidel Staff Portal";
    }

    private static String fileName(String what, List<Employee> people, boolean all, LocalDate from, LocalDate to) {
        String who = all ? "Team" : people.getFirst().getEmpNumber();
        return what + "_" + who.replaceAll("[^A-Za-z0-9-]", "") + "_" + from + "_to_" + to + ".xlsx";
    }

    private LocalDateTime time(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, zone);
    }

    /** Work log times are kept in the server's clock time; shown in the office time zone. */
    private LocalDateTime local(LocalDateTime serverTime) {
        return serverTime == null ? null
                : serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
    }

    private static String dow(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String brand(Employee e) {
        return e.getBrand() == null ? null : e.getBrand().getName();
    }

    private static String designation(Employee e) {
        return e.getDesignation() == null ? null : e.getDesignation().getName();
    }

    private static String mode(WorkMode m) {
        return switch (m) {
            case OFFICE -> "Office";
            case WFH -> "Work from home";
            case FIELD -> "Field";
        };
    }

    private static String flag(String f) {
        return switch (f) {
            case AttendanceRecord.LOCATION_MISSING -> "Location missing";
            case AttendanceRecord.LOW_ACCURACY -> "Low location accuracy";
            default -> f;
        };
    }

    private static String where(AttendanceRecord.Point p) {
        return p.latitude() == null || p.longitude() == null ? null
                : String.format(Locale.ROOT, "%.5f, %.5f", p.latitude(), p.longitude());
    }

    private static String flags(AttendanceRecord x) {
        List<String> all = new ArrayList<>(x.checkIn().flags().stream().map(f -> "In: " + flag(f)).toList());
        if (x.checkOut() != null) {
            x.checkOut().flags().stream().map(f -> "Out: " + flag(f)).forEach(all::add);
        }
        return all.isEmpty() ? null : String.join(", ", all);
    }

    private static String device(AttendanceRecord x) {
        AttendanceRecord.Device d = x.device();
        if (d == null) {
            return null;
        }
        String s = String.join(" · ", java.util.stream.Stream.of(d.platform(), d.os(), d.appVersion())
                .filter(v -> v != null && !v.isBlank()).toList());
        return s.isEmpty() ? null : s;
    }
}
