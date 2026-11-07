/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.StorageDtos.*;
import com.amsidel.backend.entity.StoredFileCategory;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** How much is stored, the stored files, and deleting them: seeing needs Storage, deleting its own tick. */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService service;

    /** The figures only, so the Overview's section does not need the Storage page. */
    @Checkpoint({Menus.STORAGE_USAGE, Menus.OVERVIEW_STORAGE})
    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "false") boolean refresh) {
        return service.summary(me, refresh);
    }

    @Checkpoint({Menus.STORAGE_USAGE})
    @GetMapping("/files")
    public FilePage files(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) StoredFileCategory category, @RequestParam(required = false) String fileType,
                          @RequestParam(required = false) Long brandId, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "uploadedAt") String sort, @RequestParam(defaultValue = "true") boolean descending) {
        return service.list(me, category, fileType, brandId, q, page, size, sort, descending);
    }

    /** Opened in the browser, never cached: these can be personal documents. */
    @Checkpoint({Menus.STORAGE_USAGE})
    @GetMapping("/files/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        FileContent f = service.content(me, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.name()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(f.bytes());
    }

    @Checkpoint({Menus.STORAGE_DELETE})
    @PostMapping("/files/delete")
    public DeleteResult delete(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody DeleteBody body) {
        return service.delete(me, body.ids());
    }
}
