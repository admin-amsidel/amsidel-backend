package com.amsidel.backend.service;

import com.amsidel.backend.dto.BrandRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.BrandLogo;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandLogoRepository;
import com.amsidel.backend.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brandRepository;
    private final BrandLogoRepository logoRepository;

    /** Logos are small images; 1 MB is plenty. */
    private static final int MAX_LOGO = 1024 * 1024;

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
        brand.setAddress(blankToNull(request.getAddress()));
        brand.setLaunchedDate(request.getLaunchedDate());
        brand.setOwners(owners(request.getOwners()));
        brand.setGstNumber(request.getGstNumber() == null || request.getGstNumber().isBlank() ? null
                : request.getGstNumber().strip().toUpperCase(Locale.ROOT));
        brand.setCategory(blankToNull(request.getCategory()));
        brand.setContactNumber(blankToNull(request.getContactNumber()));
        brand.setEmail(request.getEmail() == null || request.getEmail().isBlank() ? null
                : request.getEmail().strip().toLowerCase(Locale.ROOT));
        brand.setBankAccountNumber(blankToNull(request.getBankAccount()));
        brand.setIfscCode(request.getIfsc() == null || request.getIfsc().isBlank() ? null
                : request.getIfsc().strip().toUpperCase(Locale.ROOT));
        brand.setUpiId(request.getUpi() == null || request.getUpi().isBlank() ? null
                : request.getUpi().strip().toLowerCase(Locale.ROOT));
    }

    // ---- logo ----

    @Transactional
    public Brand saveLogo(Long id, MultipartFile file) {
        Brand brand = brandRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Brand not found: " + id));
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a logo to upload");
        }
        if (file.getSize() > MAX_LOGO) {
            throw ApiException.badRequest("The logo must be 1 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The logo could not be read; please try again");
        }
        String type = imageType(bytes);
        if (type == null) {
            throw ApiException.badRequest("The logo must be a PNG or JPEG image");
        }
        BrandLogo logo = logoRepository.findById(id).orElseGet(BrandLogo::new);
        logo.setBrandId(id);
        logo.setContent(bytes);
        logo.setContentType(type);
        logo.setUpdatedAt(Instant.now());
        logoRepository.save(logo);
        brand.setLogoUpdatedAt(logo.getUpdatedAt());
        return brand;
    }

    @Transactional
    public Brand deleteLogo(Long id) {
        Brand brand = brandRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Brand not found: " + id));
        logoRepository.deleteById(id);
        brand.setLogoUpdatedAt(null);
        return brand;
    }

    @Transactional(readOnly = true)
    public BrandLogo logo(Long id) {
        return logoRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No logo for brand " + id));
    }

    /** By the first bytes; PNG and JPEG only (both work in payslip PDFs). */
    static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    /** "anu ,  ravi,,Sibin " -> "anu, ravi, Sibin". */
    private static String owners(String s) {
        if (s == null) {
            return null;
        }
        String joined = Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
