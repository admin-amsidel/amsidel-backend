package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;

import java.time.Instant;
import java.time.LocalDate;

/** Employee as sent to the web app; never includes the PIN hash or lockout counters. */
public record EmployeeResponse(
        Long id,
        String empNumber,
        String userId,
        String name,
        Role role,
        Long brandId,
        String brandCode,
        String brandName,
        Long designationId,
        String designationName,
        Integer levelNumber,
        String levelCode,
        Long reportsToId,
        String reportsToName,
        String address,
        String phone,
        String photoPath,
        Instant photoUpdatedAt,
        LocalDate joinedDate,
        LocalDate dob,
        String bloodGroup,
        String personalEmail,
        String companyEmail,
        String emergencyContact,
        boolean active,
        boolean mustChangePassword,
        boolean locked,
        Instant lastLoginAt,
        Long userTypeId,
        String userTypeName) {

    /** Call inside a transaction: reads the brand, designation and manager. */
    public static EmployeeResponse from(Employee e) {
        Brand b = e.getBrand();
        Designation d = e.getDesignation();
        Employee boss = e.getReportsTo();
        return new EmployeeResponse(
                e.getId(),
                e.getEmpNumber(),
                e.getUserId(),
                e.getName(),
                e.getRole(),
                b == null ? null : b.getId(),
                b == null ? null : b.getCode(),
                b == null ? null : b.getName(),
                d == null ? null : d.getId(),
                d == null ? null : d.getName(),
                d == null ? null : d.getLevelNumber(),
                d == null ? null : d.getLevelCode(),
                boss == null ? null : boss.getId(),
                boss == null ? null : boss.getName(),
                e.getAddress(),
                e.getPhone(),
                e.getPhotoPath(),
                e.getPhotoUpdatedAt(),
                e.getJoinedDate(),
                e.getDob(),
                e.getBloodGroup(),
                e.getPersonalEmail(),
                e.getCompanyEmail(),
                e.getEmergencyContact(),
                e.isActive(),
                e.isMustChangePassword(),
                e.getLockedUntil() != null && e.getLockedUntil().isAfter(Instant.now()),
                e.getLastLoginAt(),
                e.getUserType() == null ? null : e.getUserType().getId(),
                e.getUserType() == null ? null : e.getUserType().getName());
    }
}
