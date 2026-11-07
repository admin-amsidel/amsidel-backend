/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ManualSalesDtos.*;
import com.amsidel.backend.entity.ApprovalStatus;
import com.amsidel.backend.entity.ManualSalesKind;
import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.ManualSalesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** The Manual Sales worklog: its tasks, the status list for a work log entry, the Overview's figures and the details page. */
@RestController
@RequestMapping("/api/manual-sales")
@RequiredArgsConstructor
public class ManualSalesController {

    private final ManualSalesService service;

    @Checkpoint({Menus.MANUAL_SALES_TASKS})
    @GetMapping("/tasks")
    public List<TaskView> tasks(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) TaskStatus status,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) String code, @RequestParam(required = false) Long employeeId, @RequestParam(required = false) String q) {
        return service.tasks(me, new TaskFilter(status, from, to, code, employeeId, q));
    }

    /** The same filters, as an Excel file. */
    @Checkpoint({Menus.MANUAL_SALES_TASKS})
    @GetMapping("/tasks/export")
    public org.springframework.http.ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) TaskStatus status,
                                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                  @RequestParam(required = false) String code, @RequestParam(required = false) Long employeeId, @RequestParam(required = false) String q) {
        return AttendanceController.excel(service.exportTasks(me, new TaskFilter(status, from, to, code, employeeId, q)));
    }

    @Checkpoint({Menus.MANUAL_SALES_TASKS})
    @GetMapping("/tasks/options")
    public TaskOptions taskOptions(@AuthenticationPrincipal AuthUser me) {
        return service.taskOptions(me);
    }

    @Checkpoint({Menus.MANUAL_SALES_TASKS})
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody TaskBody body) {
        return service.createTask(me, body);
    }

    @Checkpoint({Menus.MANUAL_SALES_TASKS})
    @PutMapping("/tasks/{id}")
    public TaskView update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody TaskBody body) {
        return service.updateTask(me, id, body);
    }

    /** The project statuses to choose from on a work log entry. */
    @Checkpoint({Menus.MANUAL_SALES_LOG})
    @GetMapping("/stages")
    public List<StageOption> stages() {
        return service.stageOptions();
    }

    /** team: everyone I may see, which needs the details tick; otherwise my own. */
    @Checkpoint({Menus.MANUAL_SALES_LOG, Menus.MANUAL_SALES_DETAILS, Menus.OVERVIEW_SALES})
    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "false") boolean team, @RequestParam(required = false) Long brandId) {
        return service.summary(me, team, brandId);
    }

    @Checkpoint({Menus.MANUAL_SALES_DETAILS})
    @GetMapping("/details/options")
    public DetailOptions detailOptions(@AuthenticationPrincipal AuthUser me) {
        return service.detailOptions(me);
    }

    @Checkpoint({Menus.MANUAL_SALES_DETAILS})
    @GetMapping("/details")
    public Details details(@AuthenticationPrincipal AuthUser me,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) Long employeeId, @RequestParam(required = false) Long brandId,
                           @RequestParam(required = false) String customer, @RequestParam(required = false) String project, @RequestParam(required = false) String agent,
                           @RequestParam(required = false) ManualSalesKind kind, @RequestParam(required = false) String stageCode,
                           @RequestParam(required = false) ApprovalStatus approval) {
        return service.search(me, new Filter(from, to, employeeId, brandId, customer, project, agent, kind, stageCode, approval));
    }
}
