/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One day for a team: everyone I may look at, with their day (all entries) or null when they did not check in. */
public record AttendanceDay(LocalDate date, List<Row> people) {

    public record Row(Long employeeId, String name, String empNumber, String designationName, String brandCode,
                      String photoPath, Instant photoUpdatedAt, AttendanceDaySummary day) {

        /** Call inside a transaction: reads the designation and brand. */
        public static Row of(Employee e, AttendanceDaySummary day) {
            return new Row(e.getId(), e.getName(), e.getEmpNumber(),
                    e.getDesignation() == null ? null : e.getDesignation().getName(),
                    e.getBrand() == null ? null : e.getBrand().getCode(),
                    e.getPhotoPath(), e.getPhotoUpdatedAt(), day);
        }
    }
}
