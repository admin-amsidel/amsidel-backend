/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.OfferLetterDtos.Body;
import com.amsidel.backend.dto.OfferLetterDtos.Defaults;
import com.amsidel.backend.dto.OfferLetterDtos.Page;
import com.amsidel.backend.dto.OfferLetterDtos.PdfFile;
import com.amsidel.backend.dto.OfferLetterDtos.View;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.OfferLetterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Issue offer letter (Employees menu). Changing and deleting an issued letter each need their own tick, checked in the service. */
@RestController
@RequestMapping("/api/offer-letters")
@Checkpoint({Menus.OFFER_LETTERS})
@RequiredArgsConstructor
public class OfferLetterController {

    private final OfferLetterService service;

    @GetMapping
    public Page list(@AuthenticationPrincipal AuthUser me) {
        return service.list(me);
    }

    @GetMapping("/defaults")
    public Defaults defaults(@AuthenticationPrincipal AuthUser me, @RequestParam Long brandId) {
        return service.defaults(me, brandId);
    }

    @GetMapping("/{id}")
    public View get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.get(me, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Body body) {
        return service.create(me, body);
    }

    @PutMapping("/{id}")
    public View update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody Body body) {
        return service.update(me, id, body);
    }

    @PostMapping("/{id}/issue")
    public View issue(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.issue(me, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        PdfFile file = service.pdf(me, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }
}
