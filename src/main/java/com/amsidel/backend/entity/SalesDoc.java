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
 * A quotation or an invoice to a customer. An invoice gets its number when it is issued, which also takes its items out of stock; cancelling puts them back. Lines keep the item, price and supplier cost as they were.
 */
@Entity
@Table(name = "sales_docs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class SalesDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SalesDocKind kind;

    /** DWQTN-102026-001 / DWSLD-102026-001; null for an invoice not issued yet. */
    @Column(unique = true, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SalesDocStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private LocalDate docDate;

    /** Quotations: valid until. */
    private LocalDate validUntil;

    /** An invoice made from a quotation. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id")
    private SalesDoc quotation;

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
    /** What the items cost from the suppliers (for the margin). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal costTotal = BigDecimal.ZERO;

    /** Shown on the document. */
    @Column(length = 1000)
    private String remarks;

    @Column(length = 2000)
    private String terms;

    @OneToMany(mappedBy = "doc", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<SalesDocLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    private Instant issuedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by")
    private Employee issuedBy;

    private Instant cancelledAt;

    @Column(length = 300)
    private String cancelReason;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
