/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MeetingDtos.Body;
import com.amsidel.backend.dto.MeetingDtos.Page;
import com.amsidel.backend.dto.MeetingDtos.Person;
import com.amsidel.backend.dto.MeetingDtos.View;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MeetingService;
import com.amsidel.backend.service.TeamDashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** The dashboard's calendar: everyone reads their own meetings, and their team's (team=true); adding, changing and deleting need the Add meetings tick. */
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService service;
    private final TeamDashboardService team;

    @GetMapping
    public Page mine(@AuthenticationPrincipal AuthUser me,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(defaultValue = "false") boolean team,
                     @RequestParam(required = false) Long brandId) {
        return team ? service.ofPeople(me, this.team.memberIds(me, brandId), from, to) : service.mine(me, from, to);
    }

    @Checkpoint({Menus.MEETING_ADD})
    @GetMapping("/people")
    public List<Person> people(@AuthenticationPrincipal AuthUser me) {
        return service.people(me);
    }

    @Checkpoint({Menus.MEETING_ADD})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Body body) {
        return service.create(me, body);
    }

    @Checkpoint({Menus.MEETING_ADD})
    @PutMapping("/{id}")
    public View update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody Body body) {
        return service.update(me, id, body);
    }

    @Checkpoint({Menus.MEETING_ADD})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
    }
}
