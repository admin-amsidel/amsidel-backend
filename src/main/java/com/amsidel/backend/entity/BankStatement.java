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
import java.util.ArrayList;
import java.util.List;

/**
 * A brand's bank statement, uploaded from Excel: its lines (date, description, credit, debit, balance) are tallied
 * against the payments, salaries and other income and expenses in the portal. Statements of a brand do not overlap.
 */
@Entity
@Table(name = "bank_statements")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(nullable = false, length = 200)
    private String fileName;

    @Column(nullable = false)
    private LocalDate fromDate;

    @Column(nullable = false)
    private LocalDate toDate;

    /** Before the first line; null when the file has no balance column. */
    @Column(precision = 14, scale = 2)
    private BigDecimal openingBalance;

    @Column(precision = 14, scale = 2)
    private BigDecimal closingBalance;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalCredit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDebit = BigDecimal.ZERO;

    @Column(nullable = false)
    private int lineCount;

    @Column(length = 500)
    private String remarks;

    @OneToMany(mappedBy = "statement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<BankLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private Employee uploadedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant uploadedAt;
}
