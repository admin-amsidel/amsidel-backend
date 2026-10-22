package com.amsidel.backend.controller;

import com.amsidel.backend.dto.*;
import com.amsidel.backend.entity.WorkHourRule;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.WorkLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Work log. With My work log, people log their own hours; reading someone else's log and setting their hours is
 * checked person by person in WorkLogService; changing the level rules needs the Work hour rules menu.
 */
@RestController
@RequestMapping("/api/worklog")
@RequiredArgsConstructor
public class WorkLogController {

    private final WorkLogService workLogService;

    /** Calendar data for me, or for employeeId. */
    @Checkpoint({Menus.WORKLOG_MY})
    @GetMapping
    public WorkLogMonth month(@AuthenticationPrincipal AuthUser me,
                              @RequestParam(required = false) Long employeeId,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workLogService.month(me, employeeId, from, to);
    }

    @Checkpoint({Menus.WORKLOG_MY})
    @GetMapping("/team")
    public List<TeamMember> team(@AuthenticationPrincipal AuthUser me) {
        return workLogService.team(me);
    }

    @Checkpoint({Menus.WORKLOG_MY})
    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkLogEntry add(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody WorkLogRequest request) {
        return workLogService.add(me, request);
    }

    @Checkpoint({Menus.WORKLOG_MY})
    @PutMapping("/entries/{id}")
    public WorkLogEntry update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                               @Valid @RequestBody WorkLogRequest request) {
        return workLogService.update(me, id, request);
    }

    @Checkpoint({Menus.WORKLOG_MY})
    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        workLogService.delete(me, id);
    }

    @Checkpoint({Menus.WORKLOG_MY, Menus.WORK_RULES})
    @GetMapping("/rules")
    public List<WorkHourRule> rules() {
        return workLogService.rules();
    }

    @Checkpoint({Menus.WORK_RULES})
    @PutMapping("/rules")
    public List<WorkHourRule> saveRules(@Valid @RequestBody List<@Valid WorkHourRuleRequest> rules) {
        return workLogService.saveRules(rules);
    }

    @Checkpoint({Menus.WORKLOG_MY})
    @PutMapping("/targets/{employeeId}")
    public WorkTarget setTarget(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId,
                                @Valid @RequestBody WorkTargetRequest request) {
        return workLogService.setTarget(me, employeeId, request);
    }
}
