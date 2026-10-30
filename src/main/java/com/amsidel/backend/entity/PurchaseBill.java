/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Goods bought from a supplier. Receiving it adds the items to stock and makes their price the supplier price; cancelling a received bill takes them out again.
 */
@Entity
@Table(name = "purchase_bills")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class PurchaseBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** DWPUR-102026-001. */
    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /** The supplier's own invoice number. */
    @Column(length = 50)
    private String supplierBillNo;

    @Column(nullable = false)
    private LocalDate billDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PurchaseStatus status;

    /** Sum of the lines. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;
    /** Off the subtotal. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    /** e.g. Transport, Installation. */
    @Column(length = 100)
    private String miscLabel;

    /** Added before GST. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal miscAmount = BigDecimal.ZERO;
    /** subtotal - discount + misc. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal taxable = BigDecimal.ZERO;

    /** GST % on the taxable amount, e.g. 18. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRate = BigDecimal.ZERO;

    /** Another state: IGST; else CGST + SGST, half each. */
    @Column(nullable = false)
    private boolean interState;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;
    /** To the rupee. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;
    /** To pay: taxable + GST + round-off. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;
    /** Payments less refunds (not voided). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal paid = BigDecimal.ZERO;

    @Column(length = 1000)
    private String remarks;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<PurchaseBillLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    private Instant receivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private Employee receivedBy;

    private Instant cancelledAt;

    @Column(length = 300)
    private String cancelReason;

    /** Who last changed it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
