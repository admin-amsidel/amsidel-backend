/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in now, for the Overview's Active users section. */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessions;

    /** The number alone, for the Overview. */
    @Checkpoint({Menus.OVERVIEW_ACTIVE_USERS})
    @GetMapping("/active/count")
    public SessionService.ActiveUsers count(@AuthenticationPrincipal AuthUser me) {
        return sessions.activeCount(me);
    }

    /** Who they are, when someone opens the list. */
    @Checkpoint({Menus.OVERVIEW_ACTIVE_USERS})
    @GetMapping("/active")
    public SessionService.ActiveUsers active(@AuthenticationPrincipal AuthUser me) {
        return sessions.active(me);
    }
}
