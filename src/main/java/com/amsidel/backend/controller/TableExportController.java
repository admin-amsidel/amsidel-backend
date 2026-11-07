/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.TableExportRequest;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.service.TableExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export to Excel on the portal's list screens: the rows a person already has on their screen, as a file. It reads
 * nothing from the database; the Export to Excel tick decides who may take a list away.
 */
@RestController
@RequestMapping("/api/exports")
@RequiredArgsConstructor
public class TableExportController {

    private final TableExportService service;

    @com.amsidel.backend.security.ExportCheck
    @PostMapping("/table")
    public ResponseEntity<byte[]> table(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody TableExportRequest request) {
        return AttendanceController.excel(service.build(me, request));
    }
}
