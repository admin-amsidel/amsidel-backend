package com.amsidel.backend.service;

import com.amsidel.backend.dto.DesignationRequest;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.DesignationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The designation ladder (owners change it; everyone signed in can read it). */
@Service
@RequiredArgsConstructor
public class DesignationService {

    private final DesignationRepository designationRepository;

    @Transactional(readOnly = true)
    public List<Designation> findAll() {
        return designationRepository.findAllByOrderByLevelNumberAscLevelCodeAsc();
    }

    @Transactional
    public Designation create(DesignationRequest request) {
        Designation designation = new Designation();
        apply(designation, request);
        return designationRepository.save(designation);
    }

    @Transactional
    public Designation update(Long id, DesignationRequest request) {
        Designation designation = designationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Designation not found: " + id));
        apply(designation, request);
        return designation;
    }

    private void apply(Designation designation, DesignationRequest request) {
        String name = request.getName().trim();
        String code = request.getLevelCode().trim();
        if (!name.equalsIgnoreCase(designation.getName()) && designationRepository.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("Designation " + name + " already exists");
        }
        if (!code.equals(designation.getLevelCode()) && designationRepository.existsByLevelCode(code)) {
            throw ApiException.conflict("Level " + code + " is already used");
        }
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
