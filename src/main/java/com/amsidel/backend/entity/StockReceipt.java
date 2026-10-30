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
import java.util.ArrayList;
import java.util.List;

/**
 * Stock received by scanning item QR labels: one line per item code (scanned again = one more). Someone allowed to
 * approve checks the quantities; approving adds them to stock, and makes the items whose code the brand does not have
 * yet from the details in their label.
 */
@Entity
@Table(name = "stock_receipts")
@Getter
@Setter
@NoArgsConstructor
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StockReceiptStatus status = StockReceiptStatus.PENDING;

    /** Where it came from: supplier, delivery note… */
    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<StockReceiptLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private Employee decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Why it was turned down, or a note with the approval. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}
