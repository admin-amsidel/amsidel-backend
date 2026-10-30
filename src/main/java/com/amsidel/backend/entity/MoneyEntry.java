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

/**
 * Other income (investment, loans, capital, other) or an other expense (transport, fixing, a discount…), for the
 * profit and loss and the bank tally. It may belong to an invoice, a project, a purchase bill or a supplier.
 * A discount with no money moved (mode null) on an invoice writes off what the customer did not pay: the invoice is
 * closed at its billed amount and the difference is an expense.
 */
@Entity
@Table(name = "money_entries", indexes = @Index(name = "ix_money_entry_date", columnList = "brand_id, entry_date"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class MoneyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** DWINC-102026-001 / DWEXP-102026-001. */
    @Column(nullable = false, unique = true, length = 30)
    private String number;

    /** What it was for: a kind set up in money_types (the column keeps the code, e.g. TRANSPORT). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "category", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private MoneyType category;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** How it was paid; null = no money moved (a discount written off). */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TradePayMode mode;

    /** Who paid or was paid: the investor, the transporter, the bank. */
    @Column(length = 150)
    private String partyName;

    @Column(length = 80)
    private String transactionId;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(length = 1000)
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_doc_id")
    private SalesDoc invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_bill_id")
    private PurchaseBill purchaseBill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** The bank statement line it was recorded from, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_line_id")
    private BankLine bankLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** A discount on an invoice with no money moved: it lowers what the customer still owes. */
    public boolean isWriteOff() {
        return category != null && category.isDiscount() && mode == null && invoice != null;
    }
}
