/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.LeaveTypeRequest;
import com.amsidel.backend.dto.OfficeHolidayResponse;
import com.amsidel.backend.dto.RestrictedHolidayRequest;
import com.amsidel.backend.dto.RestrictedHolidayResponse;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.LeaveAllocation;
import com.amsidel.backend.entity.LeaveType;
import com.amsidel.backend.entity.OfficeHoliday;
import com.amsidel.backend.entity.RestrictedHoliday;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.LeaveTypeRepository;
import com.amsidel.backend.repository.OfficeHolidayRepository;
import com.amsidel.backend.repository.RestrictedHolidayRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Leave types, the restricted holiday list (optional days people take as leave) and the office holiday list (days the
 * office is closed), each holiday mapped to a brand; changed under Leave settings. Holidays made before that may be for
 * every brand, and still apply. Someone with a brand sees and changes only theirs.
 */
@Service
@RequiredArgsConstructor
public class LeaveSettingsService {

    private final LeaveTypeRepository typeRepository;
    private final RestrictedHolidayRepository holidayRepository;
    private final OfficeHolidayRepository officeHolidayRepository;
    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public List<LeaveType> types() {
        return typeRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional
    public LeaveType createType(LeaveTypeRequest request) {
        LeaveType type = new LeaveType();
        apply(type, request);
        return typeRepository.save(type);
    }

    @Transactional
    public LeaveType updateType(Long id, LeaveTypeRequest request) {
        LeaveType type = typeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave type not found: " + id));
        apply(type, request);
        return type;
    }

    @Transactional(readOnly = true)
    public List<RestrictedHolidayResponse> holidays(AuthUser me, LocalDate from, LocalDate to, Long brandId) {
        return holidayRepository.between(from, to).stream()
                .filter(h -> visible(me, brandIdOf(h.getBrand()), brandId))
                .map(RestrictedHolidayResponse::from)
                .toList();
    }

    @Transactional
    public RestrictedHolidayResponse addHoliday(AuthUser me, RestrictedHolidayRequest request) {
        Brand brand = brandFor(me, request.getBrandId());
        boolean duplicate = holidayRepository.between(request.getDate(), request.getDate()).stream()
                .anyMatch(h -> Objects.equals(brandIdOf(h.getBrand()), request.getBrandId()));
        if (duplicate) {
            throw ApiException.conflict(request.getDate() + " is already in the restricted holiday list");
        }
        RestrictedHoliday holiday = new RestrictedHoliday();
        holiday.setHolidayDate(request.getDate());
        holiday.setName(request.getName().trim());
        holiday.setBrand(brand);
        return RestrictedHolidayResponse.from(holidayRepository.save(holiday));
    }

    @Transactional(readOnly = true)
    public List<OfficeHolidayResponse> officeHolidays(AuthUser me, LocalDate from, LocalDate to, Long brandId) {
        return officeHolidayRepository.between(from, to).stream()
                .filter(h -> visible(me, brandIdOf(h.getBrand()), brandId))
                .map(OfficeHolidayResponse::from)
                .toList();
    }

    /** Leave applied for before a holiday was added keeps its day count. */
    @Transactional
    public OfficeHolidayResponse addOfficeHoliday(AuthUser me, RestrictedHolidayRequest request) {
        Brand brand = brandFor(me, request.getBrandId());
        boolean duplicate = officeHolidayRepository.between(request.getDate(), request.getDate()).stream()
                .anyMatch(h -> Objects.equals(brandIdOf(h.getBrand()), request.getBrandId()));
        if (duplicate) {
            throw ApiException.conflict(request.getDate() + " is already an office holiday");
        }
        OfficeHoliday holiday = new OfficeHoliday();
        holiday.setHolidayDate(request.getDate());
        holiday.setName(request.getName().trim());
        holiday.setBrand(brand);
        return OfficeHolidayResponse.from(officeHolidayRepository.save(holiday));
    }

    @Transactional
    public void removeOfficeHoliday(AuthUser me, Long id) {
        OfficeHoliday holiday = officeHolidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Office holiday not found: " + id));
        requireInScope(me, brandIdOf(holiday.getBrand()));
        officeHolidayRepository.delete(holiday);
    }

    /** Leave already taken on that date stays as it is. */
    @Transactional
    public void removeHoliday(AuthUser me, Long id) {
        RestrictedHoliday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restricted holiday not found: " + id));
        requireInScope(me, brandIdOf(holiday.getBrand()));
        holidayRepository.delete(holiday);
    }

    private void apply(LeaveType type, LeaveTypeRequest request) {
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(type.getCode()) && typeRepository.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Leave code " + code + " is already used");
        }
        boolean annual = request.getAllocation() == LeaveAllocation.ANNUAL;
        if (annual && request.getAnnualQuota() == null) {
            throw ApiException.badRequest("Set the days per year for " + request.getName().trim());
        }
        boolean restricted = Boolean.TRUE.equals(request.getRestrictedDatesOnly());

        type.setCode(code);
        type.setName(request.getName().trim());
        type.setAllocation(request.getAllocation());
        type.setAnnualQuota(annual ? request.getAnnualQuota() : null);
        type.setRestrictedDatesOnly(restricted);
        // a restricted holiday is always a whole listed day
        type.setHalfDayAllowed(!restricted && Boolean.TRUE.equals(request.getHalfDayAllowed()));
        if (request.getActive() != null) {
            type.setActive(request.getActive());
        }
        if (request.getSortOrder() != null) {
            type.setSortOrder(request.getSortOrder());
        }
    }

    /** A new holiday is always for one brand, one I may change. */
    private Brand brandFor(AuthUser me, Long brandId) {
        if (brandId == null) {
            throw ApiException.badRequest("Choose the brand of this holiday");
        }
        requireInScope(me, brandId);
        return brandRepository.findById(brandId).orElseThrow(() -> ApiException.badRequest("Brand not found: " + brandId));
    }

    private static void requireInScope(AuthUser me, Long brandId) {
        if (!seesAll(me) && (brandId == null || !brandId.equals(me.brandId()))) {
            throw ApiException.forbidden("You can only change your own brand's holidays");
        }
    }

    /** Mine (and those for every brand); with a brand asked for, only that one's (and those for every brand). */
    private static boolean visible(AuthUser me, Long holidayBrand, Long asked) {
        if (holidayBrand == null) {
            return true;
        }
        return (seesAll(me) || holidayBrand.equals(me.brandId())) && (asked == null || holidayBrand.equals(asked));
    }

    private static boolean seesAll(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }

    private static Long brandIdOf(Brand brand) {
        return brand == null ? null : brand.getId();
    }
}
