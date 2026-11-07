/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.DesignationRequest;
import com.amsidel.backend.dto.DesignationView;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.DesignationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The designation ladders (owners change them; everyone signed in can read them): the group-wide one, and each
 * brand's own, since brands can rank their titles differently. Names and levels are unique within a ladder.
 */
@Service
@RequiredArgsConstructor
public class DesignationService {

    private final DesignationRepository designationRepository;
    private final com.amsidel.backend.repository.BrandRepository brandRepository;
    private final com.amsidel.backend.repository.EmployeeRepository employeeRepository;
    private final MenuAccessService menuAccess;

    /** Group-wide ones first, then each brand's ladder; each most junior first. */
    @Transactional(readOnly = true)
    public List<DesignationView> findAll() {
        return designationRepository.listWithBrands().stream().map(DesignationView::of).toList();
    }

    @Transactional
    public DesignationView create(DesignationRequest request) {
        Designation designation = new Designation();
        apply(designation, request);
        return DesignationView.of(designationRepository.save(designation));
    }

    @Transactional
    public DesignationView update(Long id, DesignationRequest request) {
        Designation designation = designationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Designation not found: " + id));
        apply(designation, request);
        return DesignationView.of(designation);
    }

    /** Only one nobody has (else switch it off); its menu access goes too. */
    @Transactional
    public void delete(Long id) {
        Designation d = designationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Designation not found: " + id));
        long n = employeeRepository.countByDesignation_Id(id);
        if (n > 0) {
            throw ApiException.conflict(n + (n == 1 ? " employee has" : " employees have") + " the designation \"" + d.getName()
                    + "\": give them another one first, or switch it off");
        }
        menuAccess.forget(com.amsidel.backend.entity.AccessSubject.DESIGNATION, Long.toString(id));
        designationRepository.delete(d);
    }

    private void apply(Designation designation, DesignationRequest request) {
        String name = request.getName().trim();
        String code = request.getLevelCode().trim();
        Brand brand = request.getBrandId() == null ? null : brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + request.getBrandId()));
        Long brandId = brand == null ? null : brand.getId();
        String ladder = brand == null ? "the group-wide designations" : brand.getCode() + "'s designations";
        // unique within its ladder: brands may each have their own Sales Executive, at their own level
        long self = designation.getId() == null ? -1L : designation.getId();
        if (designationRepository.nameTaken(name, brandId, self)) {
            throw ApiException.conflict(name + " is already one of " + ladder);
        }
        if (designationRepository.levelTaken(code, brandId, self)) {
            throw ApiException.conflict("Level " + code + " is already used in " + ladder);
        }
        if (request.getShortCode() != null) {
            String shortCode = request.getShortCode().strip().toUpperCase(java.util.Locale.ROOT);
            if (!shortCode.isEmpty() && designationRepository.shortCodeTaken(shortCode, brandId, self)) {
                throw ApiException.conflict("Short code " + shortCode + " is already used in " + ladder);
            }
            designation.setShortCode(shortCode.isEmpty() ? null : shortCode);
        }
        if (request.getDescription() != null) {
            String description = request.getDescription().strip();
            designation.setDescription(description.isEmpty() ? null : description);
        }
        // a designation moved to a brand cannot stay with people of other brands
        if (brand != null && designation.getId() != null) {
            long others = employeeRepository.countWithDesignationOutsideBrand(designation.getId(), brandId);
            if (others > 0) {
                throw ApiException.conflict(others + (others == 1 ? " person" : " people") + " outside " + brand.getName()
                        + " have \"" + designation.getName() + "\": give them another designation first");
            }
        }
        designation.setBrand(brand);
        // "6.1" must belong to level 6
        if (!code.split("\\.")[0].equals(String.valueOf(request.getLevelNumber()))) {
            throw ApiException.badRequest("Level " + code + " does not match level number " + request.getLevelNumber());
        }
        designation.setName(name);
        designation.setLevelNumber(request.getLevelNumber());
        designation.setLevelCode(code);
        if (request.getActive() != null) {
            designation.setActive(request.getActive());
        }
    }
}
