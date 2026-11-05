/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.EmployeeDocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeDocumentFileRepository extends JpaRepository<EmployeeDocumentFile, Long> {

    /** Without reading the file first, as deleteById would. */
    @Modifying
    @Query("delete from EmployeeDocumentFile f where f.documentId = :documentId")
    void remove(@Param("documentId") Long documentId);
}
