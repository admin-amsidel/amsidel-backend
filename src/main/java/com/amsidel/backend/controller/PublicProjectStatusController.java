/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ProjectUpdateDtos.PublicStatus;
import com.amsidel.backend.entity.ProjectUpdatePhoto;
import com.amsidel.backend.service.ProjectUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * The customer's project status link: no sign-in, read only, and only with the project's code and its secret. It gives
 * no way into the portal (see SecurityConfig: only these GETs are open).
 */
@RestController
@RequestMapping("/api/public/project-status/{code}/{token}")
@RequiredArgsConstructor
public class PublicProjectStatusController {

    private final ProjectUpdateService service;

    @GetMapping
    public ResponseEntity<PublicStatus> status(@PathVariable String code, @PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publicStatus(code, token));
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<byte[]> photo(@PathVariable String code, @PathVariable String token, @PathVariable Long photoId) {
        ProjectUpdatePhoto ph = service.publicPhoto(code, token, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ph.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate()).body(ph.getContent());
    }
}
