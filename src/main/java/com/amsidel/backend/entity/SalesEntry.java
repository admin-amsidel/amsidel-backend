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
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the person achieved towards a sales goal (or one of its sub-tasks): the amount, the period it was earned in,
 * the invoice numbers behind it and a note. A goal's achieved amount is the sum of its entries.
 */
@Entity
@Table(name = "goal_sales_entries")
@Getter
@Setter
@NoArgsConstructor
public class SalesEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    /** The sub-task it counts for; null when the goal has none. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private GoalTask task;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** "INV-101, INV-102": the invoices behind the amount. */
    @Column(name = "invoice_numbers", length = 1000)
    private String invoiceNumbers;

    @Column(length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
