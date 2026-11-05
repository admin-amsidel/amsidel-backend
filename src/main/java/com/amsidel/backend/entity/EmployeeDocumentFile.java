/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The file of an employee's document. Kept apart from {@link EmployeeDocument} so that listing documents loads no files. */
@Entity
@Table(name = "employee_document_files")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeDocumentFile {

    /** Same value as employee_documents.id. */
    @Id
    private Long documentId;

    /** bytea spelled out: a large length would otherwise become an "oid" large object on PostgreSQL. */
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    public EmployeeDocumentFile(Long documentId, byte[] content) {
        this.documentId = documentId;
        this.content = content;
    }
}
