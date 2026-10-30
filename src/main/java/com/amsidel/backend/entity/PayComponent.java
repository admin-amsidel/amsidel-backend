/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of a salary: Basic, House Rent Allowance, EPF Contribution, Fuel Reimbursement... The same list for every
 * employee; each salary structure gives it a monthly amount. Retired components are made inactive, not deleted,
 * so older salaries keep their lines.
 */
@Entity
@Table(name = "pay_components")
@Getter
@Setter
@NoArgsConstructor
public class PayComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short code, e.g. BASIC, HRA, EPF. */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayComponentKind kind;

    /** Order on the salary screen and payslip, within its kind. */
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /** A tax (Income Tax, Professional Tax): counted as "taxes paid" on the employee's pay dashboard. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean tax;
}
