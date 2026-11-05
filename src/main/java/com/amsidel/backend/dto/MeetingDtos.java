/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Meetings on the dashboard's calendar. */
public final class MeetingDtos {

    private MeetingDtos() {
    }

    /** People to add: the person who adds a meeting is always in it, so they need not be listed. */
    public record Body(
            @NotBlank(message = "Give the meeting a title") @Size(max = 200, message = "The title can be at most 200 characters") String title,
            @NotNull(message = "Choose the date") LocalDate date,
            @NotNull(message = "Give the start time") LocalTime startTime,
            LocalTime endTime,
            @Size(max = 300, message = "The place or link can be at most 300 characters") String location,
            @Size(max = 2000, message = "The details can be at most 2000 characters") String notes,
            List<Long> peopleIds) {
    }

    public record Person(Long id, String name, String designationName, String brandCode, String photoPath, Instant photoUpdatedAt) {
    }

    /** canChange: the person looking may change or delete it. */
    public record View(Long id, String title, LocalDate date, LocalTime startTime, LocalTime endTime, String location, String notes,
                       Long createdById, String createdByName, List<Person> people, boolean canChange) {
    }

    /** canAdd: the person has the Add meetings tick. */
    public record Page(LocalDate today, boolean canAdd, List<View> meetings) {
    }
}
