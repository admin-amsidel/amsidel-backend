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
 * Where an online payment came from, kept for refunds: the card as Razorpay reports it (network, last 4 digits, issuer,
 * type) or the payer's UPI ID, with the transaction ID, customer and bill number. Full card numbers, expiry dates and
 * CVVs never reach the portal: they are typed into Razorpay's own window, and card rules forbid keeping them.
 */
@Entity
@Table(name = "payment_sources", uniqueConstraints = @UniqueConstraint(name = "uk_payment_source_txn", columnNames = "transaction_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class PaymentSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "online_payment_id", nullable = false)
    private OnlinePayment onlinePayment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, length = 40)
    private String billNumber;

    /** The Razorpay payment ID (pay_...). */
    @Column(name = "transaction_id", nullable = false, length = 40)
    private String transactionId;

    /** card, upi, netbanking or wallet, as Razorpay says. */
    @Column(nullable = false, length = 20)
    private String method;

    /** What was charged, fee included. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Visa, MasterCard, RuPay, ... */
    @Column(length = 30)
    private String cardNetwork;

    @Column(length = 4)
    private String cardLast4;

    /** The bank that issued the card (e.g. HDFC). */
    @Column(length = 40)
    private String cardIssuer;

    /** credit, debit or prepaid. */
    @Column(length = 20)
    private String cardType;

    private Boolean cardInternational;

    /** The payer's UPI ID. */
    @Column(length = 100)
    private String vpa;

    /** Net banking bank or wallet, when paid that way. */
    @Column(length = 40)
    private String bank;

    @Column(length = 150)
    private String payerEmail;

    @Column(length = 20)
    private String payerPhone;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
