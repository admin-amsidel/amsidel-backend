/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MenuAccessEntry;
import com.amsidel.backend.dto.MenuAccessRequest;
import com.amsidel.backend.dto.MenuNode;
import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MenuAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The Menu access screen: every menu, and who has which checkpoints. Changing needs the Admin user type (SecurityConfig). */
@RestController
@RequestMapping("/api/menu-access")
@RequiredArgsConstructor
@Checkpoint(Menus.MENU_ACCESS)
public class MenuAccessController {

    private final MenuAccessService menuAccessService;

    @GetMapping("/menus")
    public List<MenuNode> menus() {
        return menuAccessService.menus();
    }

    @GetMapping
    public List<MenuAccessEntry> entries() {
        return menuAccessService.entries();
    }

    /**
     * Replaces the checkpoints of a person (EMPLOYEE/{employeeId}), a user type (ROLE/MANAGER) or a designation
     * (DESIGNATION/{id}). An empty list for a person removes their own setting, so their user type and designation count again.
     */
    @PutMapping("/{type}/{key}")
    public MenuAccessEntry save(@PathVariable AccessSubject type, @PathVariable String key,
                                @Valid @RequestBody MenuAccessRequest request) {
        return menuAccessService.save(type, key, request.getCheckpoints());
    }
}
