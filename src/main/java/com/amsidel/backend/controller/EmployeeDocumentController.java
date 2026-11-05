/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.EmployeeDocumentDtos.*;
import com.amsidel.backend.entity.DocumentType;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.EmployeeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Employee documents and profile completion. /api/auth/me/...: my own (everyone signed in). /api/employees/{id}/...:
 * someone else's, for owners and admins who manage them (SecurityConfig: changes to /api/employees need Owner or
 * Admin; the service checks they manage the person). Uploads are multipart forms with one part named "file".
 */
@RestController
@RequiredArgsConstructor
public class EmployeeDocumentController {

    private final EmployeeDocumentService documents;

    // ---- my own ----

    @GetMapping("/api/auth/me/profile-status")
    public ProfileStatus myStatus(@AuthenticationPrincipal AuthUser me) {
        return documents.status(me, null);
    }

    @GetMapping("/api/auth/me/documents")
    public List<DocumentView> myDocuments(@AuthenticationPrincipal AuthUser me) {
        return documents.list(me, null);
    }

    @GetMapping("/api/auth/me/document-quota")
    public UploadQuota myQuota(@AuthenticationPrincipal AuthUser me) {
        return documents.quota(me, null);
    }

    @PostMapping(path = "/api/auth/me/documents/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentView uploadMine(@AuthenticationPrincipal AuthUser me, @PathVariable DocumentType type,
                                   @RequestParam("file") MultipartFile file) {
        return documents.upload(me, null, type, file);
    }

    @GetMapping("/api/auth/me/documents/{type}")
    public ResponseEntity<byte[]> myFile(@AuthenticationPrincipal AuthUser me, @PathVariable DocumentType type) {
        return send(documents.file(me, null, type));
    }

    @DeleteMapping("/api/auth/me/documents/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMine(@AuthenticationPrincipal AuthUser me, @PathVariable DocumentType type) {
        documents.delete(me, null, type);
    }

    // ---- someone I manage ----

    @Checkpoint({Menus.EMP_LIST})
    @GetMapping("/api/employees/{id}/profile-status")
    public ProfileStatus status(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return documents.status(me, id);
    }

    @Checkpoint({Menus.EMP_LIST})
    @GetMapping("/api/employees/{id}/documents")
    public List<DocumentView> list(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return documents.list(me, id);
    }

    @Checkpoint({Menus.EMP_LIST})
    @GetMapping("/api/employees/{id}/document-quota")
    public UploadQuota quota(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return documents.quota(me, id);
    }

    @Checkpoint({Menus.EMP_LIST})
    @PostMapping(path = "/api/employees/{id}/documents/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentView upload(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable DocumentType type,
                               @RequestParam("file") MultipartFile file) {
        return documents.upload(me, id, type, file);
    }

    @Checkpoint({Menus.EMP_LIST})
    @GetMapping("/api/employees/{id}/documents/{type}")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable DocumentType type) {
        return send(documents.file(me, id, type));
    }

    @Checkpoint({Menus.EMP_LIST})
    @DeleteMapping("/api/employees/{id}/documents/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable DocumentType type) {
        documents.delete(me, id, type);
    }

    /** Opened in the browser (PDF or image), never cached: these are personal documents. */
    private static ResponseEntity<byte[]> send(DocumentFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.bytes());
    }
}
