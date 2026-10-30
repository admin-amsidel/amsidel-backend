/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AttendanceDay;
import com.amsidel.backend.dto.AttendanceDaySummary;
import com.amsidel.backend.dto.AttendanceRecord;
import com.amsidel.backend.dto.AttendanceToday;
import com.amsidel.backend.dto.CheckInRequest;
import com.amsidel.backend.dto.CheckOutRequest;
import com.amsidel.backend.dto.TeamMember;
import com.amsidel.backend.entity.Attendance;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.entity.WorkMode;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.AttendanceRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Check-in and check-out for people who are not always at the office (work from home, site visits).
 * Office and work-from-home: one entry per calendar day in the office time zone. Field: one entry per visit, as
 * many as needed, each closed before the next starts. The server sets every time stamp;
 * the photo is taken live by the app and stored without metadata (AttendancePhotoStore). People see their own
 * days; those above them in the reporting line, and owners/admins in scope, see their team's (TeamAccess).
 */
@Slf4j
@Service
public class AttendanceService {

    private static final int MAX_RANGE_DAYS = 62;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final TeamAccess access;
    private final AttendancePhotoStore photos;
    private final ZoneId zone;
    private final double lowAccuracyMetres;

    public AttendanceService(AttendanceRepository attendanceRepository, EmployeeRepository employeeRepository,
                             TeamAccess access, AttendancePhotoStore photos,
                             @Value("${app.attendance.zone}") ZoneId zone,
                             @Value("${app.attendance.low-accuracy-metres:200}") double lowAccuracyMetres) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.access = access;
        this.photos = photos;
        this.zone = zone;
        this.lowAccuracyMetres = lowAccuracyMetres;
    }

    @Transactional(readOnly = true)
    public AttendanceToday today(AuthUser me) {
        LocalDate today = today();
        List<AttendanceRecord> entries = attendanceRepository.findDay(me.id(), today).stream().map(AttendanceRecord::from).toList();
        return new AttendanceToday(today, zone.getId(), entries.isEmpty() ? null : AttendanceDaySummary.of(entries));
    }

    @Transactional
    public AttendanceRecord checkIn(AuthUser me, CheckInRequest request, byte[] photo, String ip) {
        Employee person = employee(me.id());
        LocalDate today = today();
        List<Attendance> sofar = attendanceRepository.findDay(person.getId(), today);
        Optional<Attendance> open = sofar.stream().filter(e -> e.getCheckOutAt() == null).findFirst();
        if (open.isPresent()) {
            Attendance o = open.get();
            throw ApiException.conflict(o.getWorkMode() == WorkMode.FIELD
                    ? "Close your visit" + (o.getPlace() == null ? "" : " to " + o.getPlace()) + " (since " + time(o.getCheckInAt())
                    + ") before starting another"
                    : "You have already checked in today at " + time(o.getCheckInAt()) + ". Check out first to check in again");
        }
        // any number of entries a day (office, home or field), one open at a time
        String place = trim(request.getPlace(), 120);
        if (request.getWorkMode() == WorkMode.FIELD && place == null) {
            throw ApiException.badRequest("Enter the place or client you are visiting");
        }
        // a field visit needs a live photo; at the office or at home it is optional
        boolean hasPhoto = photo != null && photo.length > 0;
        if (!hasPhoto && request.getWorkMode() == WorkMode.FIELD) {
            throw ApiException.badRequest("Take a photo to check in for a field visit");
        }
        byte[] jpeg = hasPhoto ? AttendancePhotoStore.clean(photo) : null;
        int entryNo = sofar.stream().mapToInt(Attendance::getEntryNo).max().orElse(0) + 1;

        Attendance a = new Attendance();
        a.setEmployee(person);
        a.setWorkDate(today);
        a.setEntryNo(entryNo);
        a.setPlace(place);
        a.setWorkMode(request.getWorkMode());
        a.setCheckInAt(Instant.now());
        Location where = location(request.getLatitude(), request.getLongitude(), request.getAccuracy());
        a.setCheckInLatitude(where.latitude());
        a.setCheckInLongitude(where.longitude());
        a.setCheckInAccuracy(where.accuracy());
        a.setCheckInLocationMissing(where.missing());
        a.setCheckInLowAccuracy(where.lowAccuracy());
        a.setCheckInIp(ip);
        a.setDevicePlatform(trim(request.getPlatform(), 40));
        a.setDeviceOs(trim(request.getOsVersion(), 60));
        a.setAppVersion(trim(request.getAppVersion(), 40));
        String path = hasPhoto ? AttendancePhotoStore.pathFor(person.getId(), today, entryNo) : null;
        a.setPhotoPath(path);
        a.setPhotoBytes(hasPhoto ? jpeg.length : 0);
        try {
            // written first, so a second check-in sent at the same moment fails here before touching the photo
            attendanceRepository.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("You have just checked in; refresh the page");
        }

        if (hasPhoto) {
            savePhoto(path, jpeg);
        }
        log.info("Check-in {} {} #{} {}{}{}", person.getEmpNumber(), today, entryNo, a.getWorkMode(), where.missing() ? " (no location)" : "",
                hasPhoto ? "" : " (no photo)");
        return AttendanceRecord.from(a);
    }

    /** Writes the photo, and removes it again when the check-in is rolled back. */
    private void savePhoto(String path, byte[] jpeg) {
        photos.save(path, jpeg);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        photos.deleteQuietly(path);
                    }
                }
            });
        }
    }

    @Transactional
    public AttendanceRecord checkOut(AuthUser me, CheckOutRequest request, String ip) {
        List<Attendance> sofar = attendanceRepository.findDay(me.id(), today());
        if (sofar.isEmpty()) {
            throw ApiException.badRequest("You have not checked in today");
        }
        Attendance a = sofar.stream().filter(e -> e.getCheckOutAt() == null).findFirst()
                .orElseThrow(() -> ApiException.conflict("You already ended work today at "
                        + time(sofar.get(sofar.size() - 1).getCheckOutAt())));
        a.setCheckOutAt(Instant.now());
        a.setCheckOutNote(trim(request.getNote(), 500));
        Location where = location(request.getLatitude(), request.getLongitude(), request.getAccuracy());
        a.setCheckOutLatitude(where.latitude());
        a.setCheckOutLongitude(where.longitude());
        a.setCheckOutAccuracy(where.accuracy());
        a.setCheckOutLocationMissing(where.missing());
        a.setCheckOutLowAccuracy(where.lowAccuracy());
        a.setCheckOutIp(ip);
        return AttendanceRecord.from(a);
    }

    /** A person's days, newest first, each with its entries (myself when employeeId is null). */
    @Transactional(readOnly = true)
    public List<AttendanceDaySummary> days(AuthUser me, Long employeeId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Ask for at most " + MAX_RANGE_DAYS + " days at a time");
        }
        Employee person = employee(employeeId == null ? me.id() : employeeId);
        if (!access.canView(me, person)) {
            throw ApiException.forbidden("You can only see your own attendance and that of people below you");
        }
        return daysOf(person, start, end);
    }

    /** The person's days start..end, newest first; the caller checks who may see them (the export). */
    List<AttendanceDaySummary> daysOf(Employee person, LocalDate start, LocalDate end) {
        Map<LocalDate, List<AttendanceRecord>> byDay = new LinkedHashMap<>();
        for (Attendance a : attendanceRepository.forEmployee(person.getId(), start, end)) {
            byDay.computeIfAbsent(a.getWorkDate(), d -> new ArrayList<>()).add(AttendanceRecord.from(a));
        }
        return byDay.values().stream().map(AttendanceDaySummary::of).toList();
    }

    /** Everyone I may look at on one day, with their day (all entries) or null. */
    @Transactional(readOnly = true)
    public AttendanceDay day(AuthUser me, LocalDate date) {
        LocalDate day = date == null ? today() : date;
        List<Employee> team = access.team(me);
        Map<Long, List<AttendanceRecord>> byPerson = team.isEmpty() ? Map.of()
                : attendanceRepository.onDay(day, team.stream().map(Employee::getId).toList()).stream()
                .map(AttendanceRecord::from)
                .collect(Collectors.groupingBy(AttendanceRecord::employeeId));
        List<AttendanceDay.Row> rows = team.stream()
                .map(e -> AttendanceDay.Row.of(e, byPerson.containsKey(e.getId()) ? AttendanceDaySummary.of(byPerson.get(e.getId())) : null))
                .toList();
        return new AttendanceDay(day, rows);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> team(AuthUser me) {
        return access.members(me);
    }

    /**
     * Owners only: removes one attendance entry (a day, or one field visit of it) and, once that is saved, its photo.
     * The person's other entries of the day stay as they are.
     */
    @Transactional
    public void delete(AuthUser me, Long attendanceId) {
        if (me.role() != Role.OWNER) {
            throw ApiException.forbidden("Only an owner can delete attendance");
        }
        Attendance a = attendanceRepository.findWithEmployee(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found: " + attendanceId));
        String path = a.getPhotoPath();
        attendanceRepository.delete(a);
        attendanceRepository.flush();
        if (path != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        photos.deleteQuietly(path);
                    }
                });
            } else {
                photos.deleteQuietly(path);
            }
        }
        log.info("Attendance {} of {} on {} #{} deleted by owner {}", attendanceId, a.getEmployee().getEmpNumber(), a.getWorkDate(),
                a.getEntryNo(), me.id());
    }

    /** The check-in photo, for the person themselves and those who may see their attendance. */
    @Transactional(readOnly = true)
    public byte[] photo(AuthUser me, Long attendanceId) {
        Attendance a = attendanceRepository.findWithEmployee(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found: " + attendanceId));
        if (!access.canView(me, a.getEmployee())) {
            throw ApiException.forbidden("You can only see your own photos and those of people below you");
        }
        if (a.getPhotoPath() == null) {
            throw new ResourceNotFoundException("No photo was taken for this check-in");
        }
        return photos.read(a.getPhotoPath());
    }

    // ---- helpers ----

    private record Location(Double latitude, Double longitude, Double accuracy, boolean missing, boolean lowAccuracy) {
    }

    /** No coordinates: LOCATION_MISSING. Accuracy unknown or worse than the limit: LOW_ACCURACY. */
    private Location location(Double latitude, Double longitude, Double accuracy) {
        if (latitude == null || longitude == null) {
            return new Location(null, null, null, true, false);
        }
        boolean low = accuracy == null || accuracy > lowAccuracyMetres;
        return new Location(latitude, longitude, accuracy, false, low);
    }

    private Employee employee(Long id) {
        return employeeRepository.findWithBrandById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private String time(Instant at) {
        return TIME.format(at.atZone(zone));
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
