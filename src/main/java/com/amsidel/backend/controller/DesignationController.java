package com.amsidel.backend.controller;

import com.amsidel.backend.dto.DesignationRequest;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.service.DesignationService;
import jakarta.validation.Valid;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Everyone signed in can read designations; changing them needs the Designations menu. */
@RestController
@RequestMapping("/api/designations")
@RequiredArgsConstructor
public class DesignationController {

    private final DesignationService designationService;

    @GetMapping
    public List<Designation> list() {
        return designationService.findAll();
    }

    @Checkpoint({Menus.DESIGNATIONS})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Designation create(@Valid @RequestBody DesignationRequest request) {
        return designationService.create(request);
    }

    @Checkpoint({Menus.DESIGNATIONS})
    @PutMapping("/{id}")
    public Designation update(@PathVariable Long id, @Valid @RequestBody DesignationRequest request) {
        return designationService.update(id, request);
    }
}
