/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.AgentDtos.*;
import com.amsidel.backend.entity.AgentCommission;
import com.amsidel.backend.entity.AgentPhoto;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Agents and their commissions, in the customer portal. Each action has its own tick. */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService service;

    @Checkpoint({Menus.AGENTS, Menus.AGENT_COMMISSIONS})
    @GetMapping("/brands")
    public List<BrandOption> brands(@AuthenticationPrincipal AuthUser me) {
        return service.brands(me);
    }

    @Checkpoint({Menus.AGENTS, Menus.AGENT_COMMISSIONS})
    @GetMapping
    public List<Row> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return service.list(me, brandId);
    }

    @Checkpoint({Menus.AGENT_ADD})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Row create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Body body) {
        return service.create(me, body);
    }

    @Checkpoint({Menus.AGENT_EDIT})
    @PutMapping("/{id}")
    public Row update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody Body body) {
        return service.update(me, id, body);
    }

    @Checkpoint({Menus.AGENTS, Menus.AGENT_COMMISSIONS})
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        AgentPhoto p = service.photo(me, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()).body(p.getContent());
    }

    /** With either tick: adding an agent includes their photo. */
    @Checkpoint({Menus.AGENT_ADD, Menus.AGENT_EDIT})
    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Row savePhoto(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return service.savePhoto(me, id, file);
    }

    @Checkpoint({Menus.AGENT_EDIT})
    @DeleteMapping("/{id}/photo")
    public Row removePhoto(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.removePhoto(me, id);
    }

    @Checkpoint({Menus.AGENT_COMMISSIONS})
    @GetMapping("/commissions")
    public CommissionPage commissions(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId, @RequestParam(required = false) Long agentId,
                                      @RequestParam(required = false) AgentCommission.Status status,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.commissions(me, brandId, agentId, status, from, to);
    }

    @Checkpoint({Menus.AGENT_COMMISSION_STATUS})
    @PutMapping("/commissions/{id}/status")
    public Commission setStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody StatusBody body) {
        return service.setStatus(me, id, body);
    }
}
