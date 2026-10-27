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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserTypeService.View create(@Valid @RequestBody UserTypeService.Body body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public UserTypeService.View update(@PathVariable Long id, @Valid @RequestBody UserTypeService.Body body) {
        return service.update(id, body);
    }

    /** Only one nobody has, and not a built-in one. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
