/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.NotificationDtos.Feed;
import com.amsidel.backend.dto.NotificationDtos.Item;
import com.amsidel.backend.entity.Meeting;
import com.amsidel.backend.entity.NotificationClear;
import com.amsidel.backend.entity.NotificationClear.Kind;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.MeetingRepository;
import com.amsidel.backend.repository.NotificationClearRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The notification bell: a meeting about to start or under way, and a meeting that has closed. Nothing runs in the
 * background: each check from a page works the notifications out from the person's meetings, so only people with the
 * Notifications tick cause any work. Clearing one stores a small mark (NotificationClear) so it does not come back, and the
 * mark goes once the meeting is too old to notify about. See the app.notifications settings.
 */
@Service
public class NotificationService {

    private final MeetingRepository meetings;
    private final NotificationClearRepository clears;
    private final Clock clock;
    private final ZoneId zone;
    private final int pollMinutes;
    private final int leadMinutes;
    private final int defaultLengthMinutes;
    private final int keepDays;

    public NotificationService(MeetingRepository meetings, NotificationClearRepository clears, Clock clock,
                               @Value("${app.leave.zone}") ZoneId zone,
                               @Value("${app.notifications.poll-minutes}") int pollMinutes,
                               @Value("${app.notifications.meeting-lead-minutes}") int leadMinutes,
                               @Value("${app.notifications.meeting-default-length-minutes}") int defaultLengthMinutes,
                               @Value("${app.notifications.keep-days}") int keepDays) {
        this.meetings = meetings;
        this.clears = clears;
        this.clock = clock;
        this.zone = zone;
        this.pollMinutes = Math.max(1, pollMinutes);
        this.leadMinutes = Math.max(0, leadMinutes);
        this.defaultLengthMinutes = Math.max(1, defaultLengthMinutes);
        this.keepDays = Math.max(0, keepDays);
    }

    @Transactional(readOnly = true)
    public Feed feed(AuthUser me) {
        return new Feed(pollMinutes, items(me, now()));
    }

    /** Clears one; clearing it twice is fine. */
    public void clear(AuthUser me, Long meetingId, Kind kind) {
        Meeting m = meetings.findFull(meetingId).orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
        boolean mine = m.getCreatedBy().getId().equals(me.id()) || m.getPeople().stream().anyMatch(p -> p.getId().equals(me.id()));
        if (!mine) {
            throw new ResourceNotFoundException("Meeting not found: " + meetingId);
        }
        mark(me.id(), meetingId, kind, m.getMeetingDate());
        forgetOld(me);
    }

    /** Clears everything showing. */
    public void clearAll(AuthUser me) {
        for (Item i : items(me, now())) {
            mark(me.id(), i.meetingId(), i.kind(), i.date());
        }
        forgetOld(me);
    }

    private List<Item> items(AuthUser me, LocalDateTime now) {
        Set<String> cleared = new HashSet<>();
        clears.findByEmployeeId(me.id()).forEach(c -> cleared.add(key(c.getMeetingId(), c.getKind())));
        LocalDate today = now.toLocalDate();
        List<Item> items = new ArrayList<>();
        for (Meeting m : meetings.forEmployee(me.id(), today.minusDays(keepDays), today)) {
            LocalDateTime start = m.getMeetingDate().atTime(m.getStartTime());
            LocalDateTime end = m.getEndTime() != null ? m.getMeetingDate().atTime(m.getEndTime()) : start.plusMinutes(defaultLengthMinutes);
            Kind kind = !now.isBefore(end) ? Kind.CLOSED : !now.isBefore(start.minusMinutes(leadMinutes)) ? Kind.STARTING : null;
            if (kind != null && !cleared.contains(key(m.getId(), kind))) {
                items.add(new Item(m.getId(), kind, !now.isBefore(start), m.getTitle(), m.getMeetingDate(), m.getStartTime(), m.getEndTime(), m.getLocation()));
            }
        }
        // what needs attention first: starting ones by time, then the closed ones, latest first
        Comparator<Item> byTime = Comparator.comparing(i -> i.date().atTime(i.startTime()));
        items.sort(Comparator.comparing((Item i) -> i.kind() == Kind.CLOSED)
                .thenComparing((a, b) -> a.kind() == Kind.CLOSED ? byTime.compare(b, a) : byTime.compare(a, b)));
        return items;
    }

    private void mark(Long employeeId, Long meetingId, Kind kind, LocalDate date) {
        if (clears.existsByEmployeeIdAndMeetingIdAndKind(employeeId, meetingId, kind)) {
            return;
        }
        NotificationClear c = new NotificationClear();
        c.setEmployeeId(employeeId);
        c.setMeetingId(meetingId);
        c.setKind(kind);
        c.setMeetingDate(date);
        try {
            clears.saveAndFlush(c);
        } catch (DataIntegrityViolationException alreadyThere) {
            // cleared a moment ago on another device
        }
    }

    private void forgetOld(AuthUser me) {
        clears.deleteOld(me.id(), now().toLocalDate().minusDays(keepDays));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(zone));
    }

    private static String key(Long meetingId, Kind kind) {
        return meetingId + ":" + kind;
    }
}
