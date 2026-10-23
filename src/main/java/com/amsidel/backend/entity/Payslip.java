package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One employee's pay for one month, made from their salary for the year. DRAFT while payroll checks and corrects it;
 * PUBLISHED when it is final. Once payroll also records the salary as credited (paidOn, how and a reference), the
 * employee sees it and can download the PDF.
 * The lines are a copy, so later changes to the salary or the pay components do not change a payslip.
 */
@Entity
@Table(name = "payslips",
        uniqueConstraints = @UniqueConstraint(name = "uk_payslip_employee_month", columnNames = {"employee_id", "period_year", "period_month"}),
        indexes = @Index(name = "ix_payslip_period", columnList = "period_year, period_month"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Payslip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The financial year it belongs to (2025 = FY 2025-26). */
    @Column(nullable = false)
    private int fyStartYear;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    /** 1 = January ... 12 = December. */
    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PayslipStatus status = PayslipStatus.DRAFT;

    @Column(nullable = false)
    private LocalDate payDate;

    /** Calendar days in the month. */
    @Column(nullable = false)
    private int daysInMonth;

    /** Working days in the month (from the salary, default 26; payroll may change it): per-day pay = earnings / this. */
    @Column(nullable = false, columnDefinition = "integer default 26")
    private int workingDays = 26;

    /** Working days employed in the month (all of them, or from a later start date), before unpaid leave. */
    @Column(nullable = false)
    private int employedDays;

    /** Loss of pay: unpaid days. Paid days = employed days - LOP days. */
    @Column(nullable = false)
    private int lopDays;

    @Column(nullable = false)
    private int paidDays;

    /** As it was when the payslip was made. */
    @Column(length = 100)
    private String designationName;

    @OneToMany(mappedBy = "payslip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private List<PayslipLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private Employee publishedBy;

    private Instant publishedAt;

    /** The day the salary was credited; null = not credited yet (the employee does not see the payslip). */
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PaymentMode paymentMode;

    /** Transaction ID: bank UTR, UPI transaction or cheque number; optional. */
    @Column(length = 60)
    private String paymentRef;

    /** Payroll's notes on the credit (e.g. paid in two parts, bank batch); not shown to the employee. */
    @Column(length = 500)
    private String paymentRemarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_recorded_by")
    private Employee paidRecordedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
