/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One document an employee keeps on file (PAN card, Aadhaar card, bank statement / passbook, offer letter, passport
 * size photo), one of each type. The file itself is on the server's disk (EmployeeDocumentStore, under
 * app.documents.dir); this row says where, what it is and who uploaded it.
 */
@Entity
@Table(name = "employee_documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_employee_document_type", columnNames = {"employee_id", "doc_type"}))
@Getter
@Setter
@NoArgsConstructor
public class EmployeeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocumentType type;

    /** The name of the file as uploaded (shown and used when downloading). */
    @Column(nullable = false, length = 200)
    private String fileName;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    /** Where the file is, below app.documents.dir, e.g. documents/12/PAN_CARD-1730620000000.pdf. */
    @Column(nullable = false, length = 300)
    private String storedPath;

    @Column(nullable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private Employee uploadedBy;
}
