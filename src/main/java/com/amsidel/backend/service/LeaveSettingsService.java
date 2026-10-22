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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Leave types with their yearly allowances, the restricted holiday list (optional days people take as leave) and
 * the office holiday list (days the office is closed for everyone); changed under Leave settings.
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
    public List<RestrictedHolidayResponse> holidays(int year) {
        return holidayRepository.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(RestrictedHolidayResponse::from)
                .toList();
    }

    @Transactional
    public RestrictedHolidayResponse addHoliday(RestrictedHolidayRequest request) {
        Brand brand = request.getBrandId() == null ? null : brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + request.getBrandId()));
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
    public List<OfficeHolidayResponse> officeHolidays(int year) {
        return officeHolidayRepository.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(OfficeHolidayResponse::from)
                .toList();
    }

    /** Leave applied for before a holiday was added keeps its day count. */
    @Transactional
    public OfficeHolidayResponse addOfficeHoliday(RestrictedHolidayRequest request) {
        Brand brand = request.getBrandId() == null ? null : brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + request.getBrandId()));
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
    public void removeOfficeHoliday(Long id) {
        if (!officeHolidayRepository.existsById(id)) {
            throw new ResourceNotFoundException("Office holiday not found: " + id);
        }
        officeHolidayRepository.deleteById(id);
    }

    /** Leave already taken on that date stays as it is. */
    @Transactional
    public void removeHoliday(Long id) {
        if (!holidayRepository.existsById(id)) {
            throw new ResourceNotFoundException("Restricted holiday not found: " + id);
        }
        holidayRepository.deleteById(id);
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

    private static Long brandIdOf(Brand brand) {
        return brand == null ? null : brand.getId();
    }
}
