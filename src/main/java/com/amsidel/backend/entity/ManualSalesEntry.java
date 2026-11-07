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
import java.time.LocalDate;

/**
 * The sales side of one work log entry on a Manual Sales worklog task: the status chosen, and for a quotation, sales order
 * or invoice its number, date and amount. The work log entry keeps the day, hours and approval: an entry counts in the
 * figures on its work log day, once its work log entry is approved.
 */
@Entity
@Table(name = "manual_sales_entries", indexes = {
        @Index(name = "ix_manual_sales_entries_employee", columnList = "employee_id"),
        @Index(name = "ix_manual_sales_entries_task", columnList = "task_id")})
@Getter
@Setter
@NoArgsConstructor
public class ManualSalesEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_log_id", nullable = false, unique = true)
    private WorkLog workLog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ManualSalesTask salesTask;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The task's brand, else the person's: it says the currency. Null only with neither. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /** A code from the project statuses. */
    @Column(name = "stage_code", nullable = false, length = 30)
    private String stageCode;

    /** Null for a status with no paper. */
    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private ManualSalesKind kind;

    @Column(length = 60)
    private String documentNumber;

    private LocalDate documentDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;
}
