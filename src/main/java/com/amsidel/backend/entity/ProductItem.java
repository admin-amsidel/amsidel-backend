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

/**
 * One item a brand sells, with its prices and its stock (kept up to date by StockMove). onHand = totalIn - totalOut.
 */
@Entity
@Table(name = "product_items",
        uniqueConstraints = {@UniqueConstraint(name = "uk_product_item_brand_code", columnNames = {"brand_id", "code"})})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ProductItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private ProductType type;

    /** Usual supplier; purchases may come from others too. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** Unique in the brand, e.g. UPVC-SW-01. */
    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    /** The supplier's own code for it. */
    @Column(length = 50)
    private String supplierItemCode;

    @Column(length = 1000)
    private String details;

    /** Sizes it comes in, comma separated: 6x4, 8x7. */
    @Column(length = 300)
    private String sizes;

    /** piece, sq ft, set, sheet… */
    @Column(nullable = false, length = 20)
    private String unit = "piece";

    /** HSN / SAC code for GST invoices. */
    @Column(length = 10)
    private String hsnCode;

    /** What one costs from the supplier (the last purchase sets it). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal supplierPrice = BigDecimal.ZERO;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal mrp = BigDecimal.ZERO;
    /** The usual selling price. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal showroomPrice = BigDecimal.ZERO;
    /** Not to be sold below this. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal minSellPrice = BigDecimal.ZERO;

    /** Count stock for it; off for made-to-order items. */
    @Column(nullable = false)
    private boolean trackStock = true;

    /** Warn when stock falls to this; null = no warning. */
    @Column(precision = 12, scale = 2)
    private BigDecimal reorderLevel;

    /** Everything received (purchases, opening stock, additions). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalIn = BigDecimal.ZERO;

    /** Everything gone out (sales, removals). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalOut = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal onHand = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

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
