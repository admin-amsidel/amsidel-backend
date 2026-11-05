/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
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

/**
 * What an agent earned on a project, written when the project is settled. Queued until it is paid; Settled once it is, with
 * the payment's details. A project has at most one.
 */
@Entity
@Table(name = "agent_commissions", uniqueConstraints = @UniqueConstraint(name = "uq_agent_commission_project", columnNames = "project_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AgentCommission {

    public enum Status { QUEUED, SETTLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** When the project was completed. */
    @Column(nullable = false)
    private LocalDate completedOn;

    @Column(length = 500)
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.QUEUED;

    // ---- the payment ----

    @Column(length = 60)
    private String transactionId;

    private LocalDate paidOn;

    @Column(length = 500)
    private String paymentRemarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_changed_by")
    private Employee statusChangedBy;

    private Instant statusChangedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
