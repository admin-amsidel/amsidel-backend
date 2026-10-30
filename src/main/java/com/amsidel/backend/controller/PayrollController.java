/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.PayrollRequests;
import com.amsidel.backend.dto.PayrollViews.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.PayrollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Yearly payroll set-up (owners, admins, and HR given the Payroll menus): pay components, and each employee's
 * salary for a financial year. fy = the year the financial year starts in (2025 = FY 2025-26).
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
@Checkpoint({Menus.PAYROLL_SALARIES})
public class PayrollController {

    private final PayrollService service;

    // ---- pay components ----

    @Checkpoint({Menus.PAYROLL_SALARIES, Menus.PAY_COMPONENTS})
    @GetMapping("/components")
    public List<ComponentView> components() {
        return service.components();
    }

    @Checkpoint({Menus.PAY_COMPONENTS})
    @PostMapping("/components")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentView addComponent(@Valid @RequestBody PayrollRequests.Component body) {
        return service.saveComponent(null, body);
    }

    @Checkpoint({Menus.PAY_COMPONENTS})
    @PutMapping("/components/{id}")
    public ComponentView updateComponent(@PathVariable Long id, @Valid @RequestBody PayrollRequests.Component body) {
        return service.saveComponent(id, body);
    }

    @Checkpoint({Menus.PAY_COMPONENTS})
    @DeleteMapping("/components/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComponent(@PathVariable Long id) {
        service.deleteComponent(id);
    }

    // ---- salaries ----

    /** Everyone with their salary totals for the year (this financial year when fy is not given). */
    @GetMapping("/salaries")
    public YearView year(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Integer fy) {
        return service.year(me, fy);
    }

    /** The income tax under both regimes for a salary being typed (not saved). */
    @PostMapping("/tax-preview")
    public java.util.Map<String, com.amsidel.backend.service.IncomeTax.Calc> taxPreview(@RequestParam int fy,
                                                                                         @Valid @RequestBody PayrollRequests.TaxPreview body) {
        return service.taxPreview(fy, body);
    }

    @GetMapping("/salaries/{employeeId}")
    public SalaryView salary(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId, @RequestParam int fy) {
        return service.salary(me, employeeId, fy);
    }

    @PutMapping("/salaries/{employeeId}")
    public SalaryView save(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId, @RequestParam int fy,
                           @Valid @RequestBody PayrollRequests.Salary body) {
        return service.save(me, employeeId, fy, body);
    }

    @DeleteMapping("/salaries/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId, @RequestParam int fy) {
        service.delete(me, employeeId, fy);
    }
}
