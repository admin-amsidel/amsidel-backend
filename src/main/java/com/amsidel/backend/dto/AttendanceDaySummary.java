package com.amsidel.backend.dto;

import com.amsidel.backend.entity.WorkMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One person's day, put together from its entries: first check-in, last check-out, the time inside the entries,
 * the work modes and any location flags; the entries themselves (oldest first) for the expanded view.
 */
public record AttendanceDaySummary(
        Long employeeId,
        String name,
        String empNumber,
        LocalDate workDate,
        Instant firstIn,
        /** Null while an entry is still open. */
        Instant lastOut,
        /** Sum of the closed entries. */
        long workedMinutes,
        /** An entry is still open (working, or at a visit). */
        boolean open,
        List<WorkMode> modes,
        List<String> flags,
        List<AttendanceRecord> entries
) {

    /** From one person's entries for one day, in entry order. */
    public static AttendanceDaySummary of(List<AttendanceRecord> entries) {
        AttendanceRecord first = entries.get(0);
        boolean open = entries.stream().anyMatch(e -> e.checkOut() == null);
        Instant lastOut = open ? null : entries.stream().map(e -> e.checkOut().at()).max(Instant::compareTo).orElse(null);
        long worked = entries.stream().map(AttendanceRecord::workedMinutes).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        List<WorkMode> modes = List.copyOf(new LinkedHashSet<>(entries.stream().map(AttendanceRecord::workMode).toList()));
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        for (AttendanceRecord e : entries) {
            flags.addAll(e.checkIn().flags());
            if (e.checkOut() != null) {
                flags.addAll(e.checkOut().flags());
            }
        }
        return new AttendanceDaySummary(first.employeeId(), first.name(), first.empNumber(), first.workDate(),
                first.checkIn().at(), lastOut, worked, open, modes, List.copyOf(flags), List.copyOf(entries));
    }
}
