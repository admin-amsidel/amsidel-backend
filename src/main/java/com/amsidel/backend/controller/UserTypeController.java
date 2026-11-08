/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.service.UserTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** User types (portal access): read by everyone signed in; added, changed and deleted by owners and admins (SecurityConfig). */
@RestController
@RequestMapping("/api/user-types")
@RequiredArgsConstructor
public class UserTypeController {

    private final UserTypeService service;

    @GetMapping
    public List<UserTypeService.View> list() {
        return service.list();
    }

    @com.amsidel.backend.security.Checkpoint({com.amsidel.backend.security.Menus.USER_TYPES})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserTypeService.View create(@org.springframework.security.core.annotation.AuthenticationPrincipal com.amsidel.backend.security.AuthUser me,
                                       @Valid @RequestBody UserTypeService.Body body) {
        return service.create(me, body);
    }

    /** Only one nobody has. */
    @com.amsidel.backend.security.Checkpoint({com.amsidel.backend.security.Menus.USER_TYPES})
    @PutMapping("/{id}")
    public UserTypeService.View update(@org.springframework.security.core.annotation.AuthenticationPrincipal com.amsidel.backend.security.AuthUser me,
                                       @PathVariable Long id, @Valid @RequestBody UserTypeService.Body body) {
        return service.update(me, id, body);
    }

    /** Only one nobody has, and not a built-in one. */
    @com.amsidel.backend.security.Checkpoint({com.amsidel.backend.security.Menus.USER_TYPES})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@org.springframework.security.core.annotation.AuthenticationPrincipal com.amsidel.backend.security.AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
    }
}
