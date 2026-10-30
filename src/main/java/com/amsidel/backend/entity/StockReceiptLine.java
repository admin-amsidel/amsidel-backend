/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One item code of a scanned stock receipt: how many were scanned, and the label's details (to make a new item). */
@Entity
@Table(name = "stock_receipt_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private StockReceipt receipt;

    @Column(name = "item_code", nullable = false, length = 30)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    /** The label as scanned (JSON), for a new item's details. */
    @Column(nullable = false, length = 4000)
    private String payload;

    /** Scanned. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    /** Taken into stock on approval (the approver may correct it); null until then. */
    @Column(name = "approved_quantity", precision = 12, scale = 2)
    private BigDecimal approvedQuantity;

    /** The item it went to (an existing one, or the one made on approval). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private ProductItem item;

    /** On approval: a new item was made from the label. */
    @Column(name = "created_item", nullable = false)
    private boolean createdItem;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
