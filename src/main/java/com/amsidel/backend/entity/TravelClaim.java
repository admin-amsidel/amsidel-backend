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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One trip an employee claims travel allowance for: typed in (MANUAL) or tracked with GPS stops (GPS). The amount is the
 * distance times the person's rate per km at the time, plus food and other expenses. It goes to their approver, who may
 * change the amount; once approved it waits for settlement, then is settled with the payment's details.
 */
@Entity
@Table(name = "travel_claims", indexes = {@Index(name = "ix_travel_claim_employee", columnList = "employee_id, meeting_date"),
        @Index(name = "ix_travel_claim_status", columnList = "status")})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class TravelClaim {

    public enum Method { MANUAL, GPS }

    /** IN_PROGRESS: a GPS trip on the way; TO_CONFIRM: back, the distance waits for the person to confirm it. */
    public enum Status { IN_PROGRESS, TO_CONFIRM, SUBMITTED, APPROVED, REJECTED, WAITING_SETTLEMENT, SETTLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TA-0001: given when made. */
    @Column(nullable = false, length = 20, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** The customer from the project portal's list; null = a name typed in (customerName). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false, length = 150)
    private String customerName;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    @Column(length = 200)
    private String fromLocation;

    @Column(length = 200)
    private String toLocation;

    @Column(length = 200)
    private String meetingPoint;

    @Column(length = 500)
    private String notes;

    // ---- distance ----
    /** What is claimed: typed in, or the GPS distance as confirmed (or changed) by the person. */
    @Column(precision = 10, scale = 2)
    private BigDecimal distanceKm;

    /** GPS trips: the shortest and the longest route through the stops in their order. */
    @Column(precision = 10, scale = 2)
    private BigDecimal shortestKm;

    @Column(precision = 10, scale = 2)
    private BigDecimal longestKm;

    /** GOOGLE (Google Maps routes) or STRAIGHT_LINE (estimated from the GPS points). */
    @Column(length = 20)
    private String distanceSource;

    /** The person changed the GPS distance (needs the Change travel distance tick). */
    private Boolean distanceChanged;

    // ---- money, in the brand's currency ----
    @Column(precision = 10, scale = 2)
    private BigDecimal ratePerKm;

    @Column(precision = 14, scale = 2)
    private BigDecimal travelAmount;

    @Column(precision = 14, scale = 2)
    private BigDecimal foodExpense;

    @Column(precision = 14, scale = 2)
    private BigDecimal otherExpense;

    /** Travel plus food plus other expenses. */
    @Column(precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    private Instant submittedAt;

    // ---- the approver's decision ----
    /** What is allowed: the claimed amount, or the approver's own figure. */
    @Column(precision = 14, scale = 2)
    private BigDecimal approvedAmount;

    @Column(length = 500)
    private String decisionRemarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private Employee decidedBy;

    private Instant decidedAt;

    // ---- settlement ----
    private Instant waitingSince;

    @Column(length = 60)
    private String transactionId;

    private LocalDate paymentDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal paymentAmount;

    @Column(length = 500)
    private String settlementRemarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settled_by")
    private Employee settledBy;

    private Instant settledAt;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    private List<TravelStop> stops = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
