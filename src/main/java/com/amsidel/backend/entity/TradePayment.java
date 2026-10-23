package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Money received from a customer for an invoice (or refunded), or paid to a supplier for a purchase bill (or refunded by them). Never deleted: a mistake is voided with a reason, for the audit.
 */
@Entity
@Table(name = "trade_payments")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class TradePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradeParty party;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradePaymentKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_doc_id")
    private SalesDoc invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_bill_id")
    private PurchaseBill purchaseBill;

    @Column(nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradePayMode mode;

    /** UTR, UPI transaction, cheque or card reference. */
    @Column(length = 80)
    private String transactionId;

    /** Bank and account it came from / went to. */
    @Column(length = 200)
    private String bankDetails;

    @Column(length = 80)
    private String upiId;

    @Column(length = 500)
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private Employee recordedBy;

    @Column(nullable = false)
    private boolean voided;

    private Instant voidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by")
    private Employee voidedBy;

    @Column(length = 300)
    private String voidReason;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
