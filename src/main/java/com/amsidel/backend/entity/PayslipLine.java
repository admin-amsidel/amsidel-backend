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

/**
 * One line of a payslip, copied from the pay component when the payslip was made (code, name, kind, tax), with the
 * month's amount and the salary's full monthly amount (for working out pay for part of a month again).
 */
@Entity
@Table(name = "payslip_lines")
@Getter
@Setter
@NoArgsConstructor
public class PayslipLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payslip_id", nullable = false)
    private Payslip payslip;

    /** The component it came from; null if that component was later deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    private PayComponent component;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayComponentKind kind;

    @Column(nullable = false)
    private boolean tax;

    /** Earnings first, then deductions, then reimbursements; in the component order within each. */
    @Column(nullable = false)
    private int sortOrder;

    /** The salary's amount for a full month. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fullAmount;

    /** This month's amount. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
