package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.Employee;

import java.time.Instant;

/** One person in a reporting line or org chart: only what is shown in a chart box, no personal details. */
public record OrgNode(
        Long id,
        String name,
        String empNumber,
        String designationName,
        Integer levelNumber,
        String levelCode,
        String brandCode,
        Long reportsToId,
        String photoPath,
        Instant photoUpdatedAt) {

    /** Call inside a transaction: reads the designation, brand and manager. */
    public static OrgNode from(Employee e) {
        Designation d = e.getDesignation();
        return new OrgNode(
                e.getId(),
                e.getName(),
                e.getEmpNumber(),
                d == null ? null : d.getName(),
                d == null ? null : d.getLevelNumber(),
                d == null ? null : d.getLevelCode(),
                e.getBrand() == null ? null : e.getBrand().getCode(),
                e.getReportsTo() == null ? null : e.getReportsTo().getId(),
                e.getPhotoPath(),
                e.getPhotoUpdatedAt());
    }
}
