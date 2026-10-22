package com.amsidel.backend.service;

import com.amsidel.backend.dto.BrandRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public List<Brand> findAll() {
        return brandRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public Brand create(BrandRequest request) {
        Brand brand = new Brand();
        apply(brand, request);
        return brandRepository.save(brand);
    }

    @Transactional
    public Brand update(Long id, BrandRequest request) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found: " + id));
        apply(brand, request);
        return brand;
    }

    private void apply(Brand brand, BrandRequest request) {
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(brand.getCode()) && brandRepository.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Brand code " + code + " is already used");
        }
        brand.setCode(code);
        brand.setName(request.getName().trim());
        if (request.getActive() != null) {
            brand.setActive(request.getActive());
        }
    }
}
