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

import java.time.Instant;

/**
 * One step in the life of a product-portal record, never changed afterwards: who did what and when (made, changed
 * with the before -> after values, sent, issued, paid, voided, cancelled, received, stock changed…).
 */
@Entity
@Table(name = "trade_history", indexes = @Index(name = "ix_trade_history_subject", columnList = "subject_type, subject_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private TradeSubject subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** The project it belongs to (the project itself, its tasks, its bills); kept as a number so it outlives a delete. */
    @Column(name = "project_id")
    private Long projectId;

    /** The bill number or name at the time, so the trail reads well even after a delete. */
    @Column(length = 200)
    private String subjectLabel;

    /** CREATED, UPDATED, STATUS, INVOICED, ISSUED, CANCELLED, DELETED, PAYMENT, REFUND, VOIDED, RECEIVED, STOCK. */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 2000)
    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "done_by")
    private Employee doneBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant doneAt;
}
