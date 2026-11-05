/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.WorkApprovalDtos.Decided;
import com.amsidel.backend.dto.WorkApprovalDtos.Decision;
import com.amsidel.backend.dto.WorkApprovalDtos.Queue;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.WorkApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The approver's queue: attendance and work log entries waiting for them. */
@RestController
@RequestMapping("/api/work-approvals")
@Checkpoint({Menus.WORK_APPROVALS})
@RequiredArgsConstructor
public class WorkApprovalController {

    private final WorkApprovalService service;

    @GetMapping
    public Queue queue(@AuthenticationPrincipal AuthUser me) {
        return service.queue(me);
    }

    @PostMapping("/decide")
    public Decided decide(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Decision decision) {
        return service.decide(me, decision);
    }
}
