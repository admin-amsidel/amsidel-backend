package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a bank statement: money in (credit) or out (debit), and the balance after it when the bank gives it. */
@Entity
@Table(name = "bank_lines", indexes = @Index(name = "ix_bank_line_date", columnList = "brand_id, txn_date"))
@Getter
@Setter
@NoArgsConstructor
public class BankLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    private BankStatement statement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** Its place in the file, from 1. */
    @Column(nullable = false)
    private int lineNo;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(length = 500)
    private String description;

    /** Cheque or reference number, when the bank gives it in its own column. */
    @Column(length = 80)
    private String reference;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2)
    private BigDecimal balance;
}
