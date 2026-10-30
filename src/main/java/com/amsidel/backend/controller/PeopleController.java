/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.OrgNode;
import com.amsidel.backend.dto.ReportingLine;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.HierarchyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Who reports to whom, for everyone signed in (person-by-person checks in HierarchyService). */
@RestController
@RequestMapping("/api/people")
@RequiredArgsConstructor
public class PeopleController {

    private final HierarchyService hierarchyService;

    @GetMapping("/me/line")
    public ReportingLine myLine(@AuthenticationPrincipal AuthUser me) {
        return hierarchyService.line(me, me.id());
    }

    @GetMapping("/{id}/line")
    public ReportingLine line(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return hierarchyService.line(me, id);
    }

    @Checkpoint({Menus.ORG_CHART})
    @GetMapping("/org-chart")
    public List<OrgNode> orgChart(@AuthenticationPrincipal AuthUser me) {
        return hierarchyService.orgChart(me);
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        return PhotoResponses.image(hierarchyService.photo(id));
    }
}
