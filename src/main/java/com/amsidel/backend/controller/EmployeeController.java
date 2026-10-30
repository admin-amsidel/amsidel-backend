/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.BankDetails;
import com.amsidel.backend.dto.EmployeeRequest;
import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.dto.ResetPasswordRequest;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.BankDetailsService;
import com.amsidel.backend.service.EmployeeService;
import com.amsidel.backend.service.PhotoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Employee records. Reading needs the All employees menu (the list also Tasks or Staff mail, to pick people);
 * changing them also needs the Owner or Admin user type (SecurityConfig). Brand limits are checked in EmployeeService.
 */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@Checkpoint({Menus.EMP_LIST})
public class EmployeeController {

    private final EmployeeService employeeService;
    private final PhotoService photoService;
    private final BankDetailsService bankDetailsService;

    @Checkpoint({Menus.EMP_LIST, Menus.TASKS, Menus.STAFF_MAIL, Menus.MENU_ACCESS})
    @GetMapping
    public List<EmployeeResponse> list(@AuthenticationPrincipal AuthUser me,
                                       @RequestParam(required = false) Long brandId) {
        return employeeService.list(me, brandId);
    }

    @GetMapping("/{id}")
    public EmployeeResponse get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return employeeService.get(me, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody EmployeeRequest request) {
        return employeeService.create(me, request);
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                   @Valid @RequestBody EmployeeRequest request) {
        return employeeService.update(me, id, request);
    }

    /** PAN and salary account: owners and admins who manage the person, and payroll. */
    @Checkpoint({Menus.EMP_LIST, Menus.PAYROLL_SALARIES})
    @GetMapping("/{id}/bank-details")
    public BankDetails.View bankDetails(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return bankDetailsService.get(me, id);
    }

    /** Owners and admins who manage the person (SecurityConfig: changes to /api/employees need Owner or Admin). */
    @PutMapping("/{id}/bank-details")
    public BankDetails.View saveBankDetails(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                            @Valid @RequestBody BankDetails.Request body) {
        return bankDetailsService.save(me, id, body);
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return PhotoResponses.image(photoService.photoOf(me, id));
    }

    /** Multipart form with one part named "file" (JPEG, PNG or WebP, up to 2 MB). */
    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeResponse uploadPhoto(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                        @RequestParam("file") MultipartFile file) {
        return photoService.uploadFor(me, id, file);
    }

    @DeleteMapping("/{id}/photo")
    public EmployeeResponse removePhoto(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return photoService.removeFor(me, id);
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                              @Valid @RequestBody ResetPasswordRequest request) {
        employeeService.resetPassword(me, id, request.getNewPassword());
    }
}
