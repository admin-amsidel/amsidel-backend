/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.SalaryStructure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SalaryStructureRepository extends JpaRepository<SalaryStructure, Long> {

    /** Every salary of one financial year, with its lines and their components. */
    @Query("select distinct s from SalaryStructure s left join fetch s.lines l left join fetch l.component"
            + " left join fetch s.updatedBy where s.fyStartYear = :fy")
    List<SalaryStructure> ofYear(@Param("fy") int fy);

    @Query("select s from SalaryStructure s left join fetch s.lines l left join fetch l.component"
            + " left join fetch s.updatedBy where s.employee.id = :employeeId and s.fyStartYear = :fy")
    Optional<SalaryStructure> find(@Param("employeeId") Long employeeId, @Param("fy") int fy);

    /** Financial years that have any salary, newest first. */
    @Query("select distinct s.fyStartYear from SalaryStructure s order by s.fyStartYear desc")
    List<Integer> years();
}
