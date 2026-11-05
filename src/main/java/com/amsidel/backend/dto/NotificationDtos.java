/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.NotificationClear;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** The notification bell. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** pollMinutes: how long the page waits before asking again, from the server's settings. */
    public record Feed(int pollMinutes, List<Item> items) {
    }

    /** kind STARTING with started false: about to start; started true: under way. CLOSED: it has ended. */
    public record Item(Long meetingId, NotificationClear.Kind kind, boolean started, String title, LocalDate date, LocalTime startTime,
                       LocalTime endTime, String location) {
    }
}
