package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Attendance;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.WorkMode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One attendance entry (a day, or one field visit of it). The photo is at GET /api/attendance/{id}/photo. */
public record AttendanceRecord(
        Long id,
        Long employeeId,
        String name,
        String empNumber,
        LocalDate workDate,
        /** 1, 2, 3… within the day. */
        int entryNo,
        WorkMode workMode,
        /** Field visits: the place or client. */
        String place,
        Point checkIn,
        /** Null while the entry is open. */
        Point checkOut,
        /** Written when closing (meeting status, how the day went). */
        String note,
        /** Check-in to check-out, once checked out. */
        Long workedMinutes,
        Device device
) {

    /** Where and when; flags are LOCATION_MISSING and LOW_ACCURACY. */
    public record Point(Instant at, Double latitude, Double longitude, Double accuracy, List<String> flags, String ip) {
    }

    public record Device(String platform, String os, String appVersion) {
    }

    public static final String LOCATION_MISSING = "LOCATION_MISSING";
    public static final String LOW_ACCURACY = "LOW_ACCURACY";

    /** Call inside a transaction: reads the employee. */
    public static AttendanceRecord from(Attendance a) {
        Employee e = a.getEmployee();
        Point in = new Point(a.getCheckInAt(), a.getCheckInLatitude(), a.getCheckInLongitude(), a.getCheckInAccuracy(),
                flags(a.isCheckInLocationMissing(), a.isCheckInLowAccuracy()), a.getCheckInIp());
        Point out = a.getCheckOutAt() == null ? null
                : new Point(a.getCheckOutAt(), a.getCheckOutLatitude(), a.getCheckOutLongitude(), a.getCheckOutAccuracy(),
                flags(a.isCheckOutLocationMissing(), a.isCheckOutLowAccuracy()), a.getCheckOutIp());
        Long worked = a.getCheckOutAt() == null ? null : Duration.between(a.getCheckInAt(), a.getCheckOutAt()).toMinutes();
        return new AttendanceRecord(a.getId(), e.getId(), e.getName(), e.getEmpNumber(), a.getWorkDate(), a.getEntryNo(),
                a.getWorkMode(), a.getPlace(), in, out, a.getCheckOutNote(), worked,
                new Device(a.getDevicePlatform(), a.getDeviceOs(), a.getAppVersion()));
    }

    private static List<String> flags(boolean missing, boolean low) {
        List<String> flags = new ArrayList<>(2);
        if (missing) {
            flags.add(LOCATION_MISSING);
        }
        if (low) {
            flags.add(LOW_ACCURACY);
        }
        return flags;
    }
}
