package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ProjectUpdateDtos.*;
import com.amsidel.backend.entity.ProjectUpdatePhoto;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.ProjectUpdateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

/** Project status updates in the portal (Project status page, and read-only on a project). */
@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_PROJECT_STATUS, Menus.PROD_PROJECTS, Menus.PROD_QUOTES, Menus.PROD_INVOICES, Menus.PROD_REPORTS})
public class ProjectUpdateController {

    private final ProjectUpdateService service;

    @GetMapping("/project-status")
    public List<StatusRow> board(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return service.board(me, brandId);
    }

    @GetMapping("/projects/{id}/updates")
    public ProjectStatusView of(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.of(me, id);
    }

    @PostMapping("/projects/{id}/updates")
    public ProjectStatusView post(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody UpdateBody body) {
        return service.post(me, id, body);
    }

    @PutMapping("/project-updates/{updateId}")
    public ProjectStatusView edit(@AuthenticationPrincipal AuthUser me, @PathVariable Long updateId, @Valid @RequestBody UpdateBody body) {
        return service.edit(me, updateId, body);
    }

    @DeleteMapping("/project-updates/{updateId}")
    public ProjectStatusView delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long updateId) {
        return service.delete(me, updateId);
    }

    /** One or more photos (field "files"), JPEG, PNG or WebP, up to 5 MB each, at most 8 an update. */
    @PostMapping("/project-updates/{updateId}/photos")
    public ProjectStatusView addPhotos(@AuthenticationPrincipal AuthUser me, @PathVariable Long updateId,
                                       @RequestParam("files") List<MultipartFile> files) {
        return service.addPhotos(me, updateId, files);
    }

    @DeleteMapping("/project-update-photos/{photoId}")
    public ProjectStatusView deletePhoto(@AuthenticationPrincipal AuthUser me, @PathVariable Long photoId) {
        return service.deletePhoto(me, photoId);
    }

    @GetMapping("/project-update-photos/{photoId}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser me, @PathVariable Long photoId) {
        ProjectUpdatePhoto ph = service.photo(me, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ph.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()).body(ph.getContent());
    }

    /** Makes (or renews) the customer's status link. */
    @PostMapping("/projects/{id}/share")
    public ProjectStatusView share(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.share(me, id);
    }

    @DeleteMapping("/projects/{id}/share")
    public ProjectStatusView unshare(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.unshare(me, id);
    }
}
