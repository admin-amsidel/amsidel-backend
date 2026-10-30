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
 * One request to pay an invoice online, and what came of it: the Razorpay order sent (bill amount, fee, total charged),
 * the Razorpay payment that paid it, and the invoice payment recorded from it. Never deleted, for the audit.
 */
@Entity
@Table(name = "online_payments", indexes = @Index(name = "ix_online_payments_order", columnList = "gateway_order_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class OnlinePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_doc_id", nullable = false)
    private SalesDoc invoice;

    /** The bill number when paid, kept as text too. */
    @Column(nullable = false, length = 40)
    private String billNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OnlineMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OnlinePayStatus status;

    /** What goes off the bill. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal billAmount;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal feePercent;

    /** The processing fee on top. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    /** What the customer is charged: bill amount + fee. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** The Razorpay key the order was made with (which account the money goes to); null for manual UPI. */
    @Column(length = 40)
    private String gatewayKeyId;

    /** null for manual UPI. */
    @Column(name = "gateway_order_id", length = 40)
    private String gatewayOrderId;

    /** Manual UPI: the brand's UPI ID the customer paid to. */
    @Column(length = 100)
    private String payeeUpiId;

    /** The transaction ID: the Razorpay payment (pay_...), or for manual UPI the UPI reference (UTR) the staff typed. */
    @Column(length = 40)
    private String gatewayPaymentId;

    @Column(length = 200)
    private String gatewaySignature;

    /** Why it failed, from the bank or Razorpay. */
    @Column(length = 500)
    private String failure;

    /** A note, e.g. paid after the bill was settled another way. */
    @Column(length = 500)
    private String note;

    /** Refunded through Razorpay so far (of the bill amount). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal refunded = BigDecimal.ZERO;

    /** The invoice payment recorded from it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_payment_id")
    private TradePayment tradePayment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private Employee requestedBy;

    private Instant paidAt;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
