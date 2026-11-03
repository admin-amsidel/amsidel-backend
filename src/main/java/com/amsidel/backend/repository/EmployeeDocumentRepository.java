/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.DocumentType;
import com.amsidel.backend.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {

    @Query("select d from EmployeeDocument d left join fetch d.uploadedBy where d.employee.id = :employeeId")
    List<EmployeeDocument> forEmployee(@Param("employeeId") Long employeeId);

    Optional<EmployeeDocument> findByEmployeeIdAndType(Long employeeId, DocumentType type);
}
