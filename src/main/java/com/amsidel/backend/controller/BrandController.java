package com.amsidel.backend.controller;

import com.amsidel.backend.dto.BrandRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.amsidel.backend.entity.BrandLogo;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Everyone signed in can read brands; only owners add or change them (see SecurityConfig). Logos are public. */
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

    /** PNG or JPEG, up to 1 MB. */
    @PostMapping("/{id}/logo")
    public Brand uploadLogo(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return brandService.saveLogo(id, file);
    }

    @DeleteMapping("/{id}/logo")
    public Brand deleteLogo(@PathVariable Long id) {
        return brandService.deleteLogo(id);
    }

    /** No sign-in needed: a logo is public (payslips, the website). Cached; the portal adds ?v=<logoUpdatedAt>. */
    @GetMapping("/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable Long id) {
        BrandLogo logo = brandService.logo(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(logo.getContent());
    }
}
