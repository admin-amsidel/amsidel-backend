/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.MeetingDtos.Body;
import com.amsidel.backend.dto.MeetingDtos.Page;
import com.amsidel.backend.dto.MeetingDtos.Person;
import com.amsidel.backend.dto.MeetingDtos.View;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Meeting;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.MeetingRepository;
import com.amsidel.backend.repository.NotificationClearRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Meetings on the dashboard's calendar. Everyone sees the meetings they added or are in; adding one needs the Add
 * meetings tick (checked on the controller). A meeting can be changed or deleted by whoever added it, while they still
 * have the tick, and by owners. People can be chosen from your own brand, or from every brand for owners and group admins.
 */
@Service
public class MeetingService {

    private static final int MAX_RANGE_DAYS = 62;

    private final MeetingRepository meetings;
    private final EmployeeRepository employees;
    private final MenuAccessService menuAccess;
    private final NotificationClearRepository notificationClears;
    private final ZoneId zone;

    public MeetingService(MeetingRepository meetings, EmployeeRepository employees, MenuAccessService menuAccess,
                          NotificationClearRepository notificationClears,
                          @Value("${app.leave.zone}") ZoneId zone) {
        this.meetings = meetings;
        this.employees = employees;
        this.menuAccess = menuAccess;
        this.notificationClears = notificationClears;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public Page mine(AuthUser me, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Ask for at most " + MAX_RANGE_DAYS + " days at a time");
        }
        return new Page(today(), menuAccess.can(me, Menus.MEETING_ADD),
                meetings.forEmployee(me.id(), from, to).stream().map(m -> view(me, m)).toList());
    }

    /** Meetings with any of these people in them: the team's calendar; the caller says who the team is. */
    @Transactional(readOnly = true)
    public Page ofPeople(AuthUser me, List<Long> ids, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Ask for at most " + MAX_RANGE_DAYS + " days at a time");
        }
        return new Page(today(), menuAccess.can(me, Menus.MEETING_ADD),
                ids.isEmpty() ? List.of() : meetings.ofPeople(ids, from, to).stream().map(m -> view(me, m)).toList());
    }

    /** Who can be put in a meeting, without the person adding it. */
    @Transactional(readOnly = true)
    public List<Person> people(AuthUser me) {
        return employees.search(scope(me)).stream()
                .filter(e -> e.isActive() && !e.getId().equals(me.id()))
                .map(MeetingService::person).toList();
    }

    @Transactional
    public View create(AuthUser me, Body body) {
        if (body.date().isBefore(today())) {
            throw ApiException.badRequest("A new meeting cannot be on a date that has passed");
        }
        Meeting m = new Meeting();
        m.setCreatedBy(employees.findById(me.id()).orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id())));
        apply(me, m, body, Set.of());
        return view(me, meetings.saveAndFlush(m));
    }

    @Transactional
    public View update(AuthUser me, Long id, Body body) {
        Meeting m = changeable(me, id);
        if (!body.date().equals(m.getMeetingDate()) && body.date().isBefore(today())) {
            throw ApiException.badRequest("A meeting cannot be moved to a date that has passed");
        }
        Set<Long> already = new HashSet<>();
        m.getPeople().forEach(p -> already.add(p.getId()));
        boolean moved = !body.date().equals(m.getMeetingDate()) || !body.startTime().equals(m.getStartTime())
                || !java.util.Objects.equals(body.endTime(), m.getEndTime());
        apply(me, m, body, already);
        if (moved) {
            // a new time is news: what was cleared about the old one no longer counts
            notificationClears.deleteForMeeting(id);
        }
        return view(me, meetings.saveAndFlush(m));
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        meetings.delete(changeable(me, id));
        notificationClears.deleteForMeeting(id);
    }

    private Meeting changeable(AuthUser me, Long id) {
        Meeting m = meetings.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + id));
        if (!canChange(me, m)) {
            throw ApiException.forbidden("Only the person who added a meeting can change or delete it");
        }
        return m;
    }

    private static boolean canChange(AuthUser me, Meeting m) {
        return me.isOwner() || m.getCreatedBy().getId().equals(me.id());
    }

    /** already: people in the meeting before this change, who stay allowed even if they have since moved brand or left. */
    private void apply(AuthUser me, Meeting m, Body body, Set<Long> already) {
        if (body.endTime() != null && !body.endTime().isAfter(body.startTime())) {
            throw ApiException.badRequest("The end time must be after the start time");
        }
        m.setTitle(body.title().strip());
        m.setMeetingDate(body.date());
        m.setStartTime(body.startTime());
        m.setEndTime(body.endTime());
        m.setLocation(StringUtils.hasText(body.location()) ? body.location().strip() : null);
        m.setNotes(StringUtils.hasText(body.notes()) ? body.notes().strip() : null);

        Long scope = scope(me);
        Set<Employee> people = new LinkedHashSet<>();
        people.add(m.getCreatedBy());
        for (Long personId : body.peopleIds() == null ? List.<Long>of() : body.peopleIds()) {
            Employee e = employees.findWithBrandById(personId)
                    .orElseThrow(() -> ApiException.badRequest("Employee not found: " + personId));
            boolean inBrand = scope == null || (e.getBrand() != null && scope.equals(e.getBrand().getId()));
            if (!already.contains(personId) && (!inBrand || !e.isActive())) {
                throw ApiException.forbidden("You can only add active people of your own brand to a meeting");
            }
            people.add(e);
        }
        m.getPeople().clear();
        m.getPeople().addAll(people);
    }

    /** Null = every brand. */
    private static Long scope(AuthUser me) {
        return me.isOwner() || me.brandId() == null ? null : me.brandId();
    }

    private View view(AuthUser me, Meeting m) {
        List<Person> people = new ArrayList<>(m.getPeople().stream().map(MeetingService::person).toList());
        people.sort(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return new View(m.getId(), m.getTitle(), m.getMeetingDate(), m.getStartTime(), m.getEndTime(), m.getLocation(), m.getNotes(),
                m.getCreatedBy().getId(), m.getCreatedBy().getName(), people,
                canChange(me, m) && menuAccess.can(me, Menus.MEETING_ADD));
    }

    private static Person person(Employee e) {
        return new Person(e.getId(), e.getName(), e.getDesignation() == null ? null : e.getDesignation().getName(),
                e.getBrand() == null ? null : e.getBrand().getCode(), e.getPhotoPath(), e.getPhotoUpdatedAt());
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }
}
