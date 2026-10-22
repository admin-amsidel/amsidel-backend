package com.amsidel.backend.controller;

import com.amsidel.backend.dto.BrandRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Everyone signed in can read brands; only owners add or change them (see SecurityConfig). */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping
    public List<Brand> list() {
        return brandService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Brand create(@Valid @RequestBody BrandRequest request) {
        return brandService.create(request);
    }

    @PutMapping("/{id}")
    public Brand update(@PathVariable Long id, @Valid @RequestBody BrandRequest request) {
        return brandService.update(id, request);
    }
}
