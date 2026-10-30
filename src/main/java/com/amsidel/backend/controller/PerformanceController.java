/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.PerformanceRequests;
import com.amsidel.backend.dto.PerformanceViews.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.PerformanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Performance: review cycles; my review (intro, skills, goal progress, self-rating, achievements); my team's
 * reviews (goals, ratings, skill validation, achievements). PerformanceService checks who is whose manager.
 */
@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Checkpoint({Menus.PERF_MY, Menus.PERF_TEAM})
public class PerformanceController {

    private final PerformanceService service;

    // ---- cycles ----

    @Checkpoint({Menus.PERF_MY, Menus.PERF_TEAM, Menus.PERF_CYCLES})
    @GetMapping("/cycles")
    public List<CycleView> cycles(@AuthenticationPrincipal AuthUser me) {
        return service.cycles(me);
    }

    @Checkpoint({Menus.PERF_CYCLES})
    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public CycleView addCycle(@Valid @RequestBody PerformanceRequests.Cycle body) {
        return service.saveCycle(null, body);
    }

    @Checkpoint({Menus.PERF_CYCLES})
    @PutMapping("/cycles/{id}")
    public CycleView updateCycle(@PathVariable Long id, @Valid @RequestBody PerformanceRequests.Cycle body) {
        return service.saveCycle(id, body);
    }

    // ---- reviews ----

    @Checkpoint({Menus.PERF_MY})
    @GetMapping("/cycles/{cycleId}/me")
    public ReviewView mine(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId) {
        return service.review(me, cycleId, null);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @GetMapping("/cycles/{cycleId}/team")
    public List<TeamRow> team(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId) {
        return service.team(me, cycleId);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @GetMapping("/cycles/{cycleId}/employees/{employeeId}")
    public ReviewView theirs(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId, @PathVariable Long employeeId) {
        return service.review(me, cycleId, employeeId);
    }

    @Checkpoint({Menus.PERF_MY})
    @PutMapping("/cycles/{cycleId}/me/intro")
    public ReviewView intro(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId,
                            @Valid @RequestBody PerformanceRequests.Intro body) {
        return service.saveIntro(me, cycleId, body.getSelfIntro());
    }

    // ---- skills ----

    @Checkpoint({Menus.PERF_MY})
    @PostMapping("/cycles/{cycleId}/me/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillView addSkill(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId,
                              @Valid @RequestBody PerformanceRequests.SkillBody body) {
        return service.addSkill(me, cycleId, body);
    }

    @Checkpoint({Menus.PERF_MY})
    @PutMapping("/skills/{id}")
    public SkillView updateSkill(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                 @Valid @RequestBody PerformanceRequests.SkillBody body) {
        return service.updateSkill(me, id, body);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @PostMapping("/cycles/{cycleId}/employees/{employeeId}/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillView addSkillFor(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId, @PathVariable Long employeeId,
                                 @Valid @RequestBody PerformanceRequests.ManagerSkill body) {
        return service.addSkillFor(me, cycleId, employeeId, body);
    }

    @DeleteMapping("/skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.deleteSkill(me, id);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @PutMapping("/skills/{id}/validation")
    public SkillView validateSkill(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                   @Valid @RequestBody PerformanceRequests.SkillValidation body) {
        return service.validateSkill(me, id, body);
    }

    // ---- goals ----

    @Checkpoint({Menus.PERF_TEAM})
    @PostMapping("/cycles/{cycleId}/employees/{employeeId}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalView addGoal(@AuthenticationPrincipal AuthUser me, @PathVariable Long cycleId, @PathVariable Long employeeId,
                            @Valid @RequestBody PerformanceRequests.GoalBody body) {
        return service.addGoal(me, cycleId, employeeId, body);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @PutMapping("/goals/{id}")
    public GoalView updateGoal(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                               @Valid @RequestBody PerformanceRequests.GoalBody body) {
        return service.updateGoal(me, id, body);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @DeleteMapping("/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.deleteGoal(me, id);
    }

    @PutMapping("/goals/{id}/progress")
    public GoalView goalProgress(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                 @Valid @RequestBody PerformanceRequests.Progress body) {
        return service.goalProgress(me, id, body.getProgress());
    }

    @PutMapping("/goals/{goalId}/tasks/{taskId}/progress")
    public GoalView taskProgress(@AuthenticationPrincipal AuthUser me, @PathVariable Long goalId, @PathVariable Long taskId,
                                 @Valid @RequestBody PerformanceRequests.Progress body) {
        return service.taskProgress(me, goalId, taskId, body.getProgress());
    }

    /** Sales goals: the person records what they achieved; progress and self rating follow. */
    @Checkpoint({Menus.PERF_MY})
    @PostMapping("/goals/{goalId}/sales-entries")
    public GoalView addSalesEntry(@AuthenticationPrincipal AuthUser me, @PathVariable Long goalId,
                                  @Valid @RequestBody PerformanceRequests.SalesEntryBody body) {
        return service.addSalesEntry(me, goalId, body);
    }

    @Checkpoint({Menus.PERF_MY})
    @PutMapping("/sales-entries/{id}")
    public GoalView updateSalesEntry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                     @Valid @RequestBody PerformanceRequests.SalesEntryBody body) {
        return service.updateSalesEntry(me, id, body);
    }

    @Checkpoint({Menus.PERF_MY})
    @DeleteMapping("/sales-entries/{id}")
    public GoalView deleteSalesEntry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.deleteSalesEntry(me, id);
    }

    @Checkpoint({Menus.PERF_MY})
    @PutMapping("/goals/{id}/self-rating")
    public GoalView selfRate(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                             @Valid @RequestBody PerformanceRequests.Rating body) {
        return service.selfRate(me, id, body);
    }

    @Checkpoint({Menus.PERF_TEAM})
    @PutMapping("/goals/{id}/manager-rating")
    public GoalView managerRate(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                @Valid @RequestBody PerformanceRequests.Rating body) {
        return service.managerRate(me, id, body);
    }

    // ---- achievements ----

    @PostMapping("/achievements")
    @ResponseStatus(HttpStatus.CREATED)
    public AchievementView addAchievement(@AuthenticationPrincipal AuthUser me,
                                          @Valid @RequestBody PerformanceRequests.AchievementBody body) {
        return service.addAchievement(me, body);
    }

    @DeleteMapping("/achievements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAchievement(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.deleteAchievement(me, id);
    }
}
