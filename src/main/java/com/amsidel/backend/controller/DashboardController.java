/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.DashboardDtos.*;
import com.amsidel.backend.dto.MeetingDtos;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.service.DashboardService;
import com.amsidel.backend.service.TeamDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Overview page, for those with its tick, and only about me or the people below me. Each part of the page is asked for
 * on its own, so the first figures show as soon as they are read and a slow part keeps nothing else waiting. A part follows
 * its page's tick: without it the answer is empty (mail: no rows). Under /team, brandId takes one brand for those who may
 * (0 = people without a brand).
 */
@RestController
@RequestMapping("/api/dashboard")
@com.amsidel.backend.security.Checkpoint({com.amsidel.backend.security.Menus.OVERVIEW})
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService me;
    private final TeamDashboardService team;

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthUser user) {
        return me.summary(user);
    }

    @GetMapping("/hours")
    public Hours hours(@AuthenticationPrincipal AuthUser user) {
        return me.hours(user);
    }

    @GetMapping("/attendance")
    public Attendance attendance(@AuthenticationPrincipal AuthUser user) {
        return me.attendance(user);
    }

    @GetMapping("/leave")
    public Leave leave(@AuthenticationPrincipal AuthUser user) {
        return me.leave(user);
    }

    @GetMapping("/tasks")
    public Tasks tasks(@AuthenticationPrincipal AuthUser user) {
        return me.tasks(user);
    }

    @GetMapping("/pay")
    public Pay pay(@AuthenticationPrincipal AuthUser user) {
        return me.pay(user);
    }

    @GetMapping("/mail")
    public List<Mail> mail(@AuthenticationPrincipal AuthUser user) {
        return me.mail(user, null);
    }

    @GetMapping("/profile")
    public Profile profile(@AuthenticationPrincipal AuthUser user) {
        return me.profile(user);
    }

    @GetMapping("/meetings-today")
    public List<MeetingDtos.View> meetingsToday(@AuthenticationPrincipal AuthUser user) {
        return me.meetingsToday(user);
    }

    @GetMapping("/team/summary")
    public TeamSummary teamSummary(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.summary(user, brandId);
    }

    @GetMapping("/team/hours")
    public Hours teamHours(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.hours(user, brandId);
    }

    @GetMapping("/team/attendance")
    public TeamAttendance teamAttendance(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.attendance(user, brandId);
    }

    @GetMapping("/team/leave")
    public Leave teamLeave(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.leave(user, brandId);
    }

    @GetMapping("/team/tasks")
    public Tasks teamTasks(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.tasks(user, brandId);
    }

    @GetMapping("/team/pay")
    public TeamPay teamPay(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.pay(user, brandId);
    }

    @GetMapping("/team/mail")
    public List<Mail> teamMail(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.mail(user, brandId);
    }

    @GetMapping("/team/profile")
    public TeamProfile teamProfile(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.profile(user, brandId);
    }

    @GetMapping("/team/meetings-today")
    public List<MeetingDtos.View> teamMeetingsToday(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.meetingsToday(user, brandId);
    }

    @GetMapping("/team/people")
    public TeamPeople teamPeople(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) Long brandId) {
        return team.teamPeople(user, brandId);
    }
}
