/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.NotificationDtos.Feed;
import com.amsidel.backend.entity.NotificationClear.Kind;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The notification bell: needs the Notifications tick, and the page does not even ask without it. */
@RestController
@RequestMapping("/api/notifications")
@Checkpoint({Menus.NOTIFICATIONS})
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public Feed feed(@AuthenticationPrincipal AuthUser me) {
        return service.feed(me);
    }

    @DeleteMapping("/{meetingId}/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal AuthUser me, @PathVariable Long meetingId, @PathVariable Kind kind) {
        service.clear(me, meetingId, kind);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAll(@AuthenticationPrincipal AuthUser me) {
        service.clearAll(me);
    }
}
