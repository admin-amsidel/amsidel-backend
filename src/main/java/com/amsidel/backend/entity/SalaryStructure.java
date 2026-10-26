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
 * One employee's salary for one financial year (April to March): the monthly amount of each pay component, from
 * effectiveFrom (the year's 1 April, or a later joining date). Yearly figures are worked out from it.
 */
@Entity
@Table(name = "salary_structures",
        uniqueConstraints = @UniqueConstraint(name = "uk_salary_employee_year", columnNames = {"employee_id", "fy_start_year"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class SalaryStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The year the financial year starts in: 2025 = FY 2025-26 (1 Apr 2025 to 31 Mar 2026). */
    @Column(name = "fy_start_year", nullable = false)
    private int fyStartYear;

    /** First day this salary is paid for, inside the financial year. */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** Working days in a month for this salary (default 26): per-day pay = monthly earnings / working days. */
    @Column(nullable = false, columnDefinition = "integer default 26")
    private int workingDays = 26;

    @Column(length = 500)
    private String notes;

    /** How the income tax is set; null = MANUAL (salaries saved before regimes came). */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TaxRegime taxRegime;

    /** Old regime: deductions and exemptions declared for the year (80C, 80D, HRA, home-loan interest...). */
    @Column(precision = 14, scale = 2)
    private java.math.BigDecimal taxDeclared;

    @OneToMany(mappedBy = "structure", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SalaryLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** The regime in force (MANUAL for salaries saved before regimes came). */
    public TaxRegime regime() {
        return taxRegime == null ? TaxRegime.MANUAL : taxRegime;
    }
}
