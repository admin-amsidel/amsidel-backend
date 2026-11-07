/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Administration > Employee generator: many test employees at once. Its own tick. */
@RestController
@RequestMapping("/api/employee-generator")
@Checkpoint({Menus.EMP_GENERATOR})
@RequiredArgsConstructor
public class EmployeeGeneratorController {

    private final EmployeeService employees;

    /** The number the next one would get. */
    @GetMapping("/next")
    public EmployeeService.NextNumber next(@AuthenticationPrincipal AuthUser me, @RequestParam Long brandId) {
        return employees.nextGenerated(me, brandId);
    }

    /** Who they can report to. */
    @GetMapping("/superiors")
    public java.util.List<EmployeeService.Superior> superiors(@AuthenticationPrincipal AuthUser me, @RequestParam Long brandId) {
        return employees.generatorSuperiors(me, brandId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeService.Generated generate(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody EmployeeService.GenerateBody body) {
        return employees.generate(me, body);
    }
}
