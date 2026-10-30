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

/** The monthly amount of one pay component in a salary structure. */
@Entity
@Table(name = "salary_lines",
        uniqueConstraints = @UniqueConstraint(name = "uk_salary_line", columnNames = {"structure_id", "component_id"}))
@Getter
@Setter
@NoArgsConstructor
public class SalaryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false)
    private SalaryStructure structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private PayComponent component;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyAmount;
}
