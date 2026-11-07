/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.ConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administration > Configuration: the settings that override the properties file. Its own tick. */
@RestController
@RequestMapping("/api/configurations")
@Checkpoint({Menus.CONFIGURATIONS})
@RequiredArgsConstructor
public class ConfigurationController {

    private final ConfigurationService service;

    @GetMapping
    public List<ConfigurationService.View> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigurationService.View create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ConfigurationService.CreateBody body) {
        return service.create(me, body);
    }

    @PutMapping("/{code}")
    public ConfigurationService.View update(@AuthenticationPrincipal AuthUser me, @PathVariable String code, @Valid @RequestBody ConfigurationService.UpdateBody body) {
        return service.update(me, code, body);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable String code) {
        service.delete(me, code);
    }
}
