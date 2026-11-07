/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.EmployeeRemovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deleting employees: its own tick. Apart from /api/employees, whose changes are for owners and admins whatever the ticks. */
@RestController
@RequestMapping("/api/employee-removal")
@RequiredArgsConstructor
public class EmployeeRemovalController {

    private final EmployeeRemovalService service;

    @Checkpoint({Menus.EMP_DELETE})
    @PostMapping
    public EmployeeRemovalService.DeleteResult delete(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody EmployeeRemovalService.DeleteBody body) {
        return service.delete(me, body.ids());
    }
}
