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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of a quotation or invoice: the item as it was then, or a free line (installation, transport).
 */
@Entity
@Table(name = "sales_doc_lines")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class SalesDocLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    private SalesDoc doc;

    /** Null for a free line. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private ProductItem item;

    @Column(length = 30)
    private String itemCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String typeName;

    @Column(length = 10)
    private String hsnCode;

    @Column(length = 50)
    private String size;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 20)
    private String unit = "piece";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** The supplier price of one, then. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(nullable = false)
    private int sortOrder;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
