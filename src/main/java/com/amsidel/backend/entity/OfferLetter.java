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
import java.util.ArrayList;
import java.util.List;

/**
 * An offer letter: everything it says is kept here, as it was written (a later change to the brand does not change it).
 * A draft can be changed freely. Once issued it has a number and a frozen PDF (OfferLetterFile), and only someone with
 * the Change or Delete issued offer letter tick can touch it. Only the brand is required before issuing, so the columns
 * are nullable.
 */
@Entity
@Table(name = "offer_letters", uniqueConstraints = @UniqueConstraint(name = "uk_offer_letter_number", columnNames = "letter_number"))
@Getter
@Setter
@NoArgsConstructor
public class OfferLetter {

    public enum Status { DRAFT, ISSUED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.DRAFT;

    /** DW/OL/2026/0001, given when issued. */
    @Column(name = "letter_number", length = 40)
    private String letterNumber;

    private Integer letterYear;

    private Integer letterSeq;

    private LocalDate issueDate;

    @Column(length = 200)
    private String companyName;

    @Column(length = 500)
    private String companyAddress;

    /** The introduction about the company. */
    @Column(length = 4000)
    private String companyIntro;

    @Column(nullable = false, length = 150)
    private String candidateName;

    @Column(length = 500)
    private String candidateAddress;

    /** The position offered. */
    @Column(length = 150)
    private String designation;

    private LocalDate joiningDate;

    private LocalDate startDate;

    @Column(precision = 5, scale = 2)
    private BigDecimal weeklyHours;

    @Column(precision = 14, scale = 2)
    private BigDecimal annualPackage;

    /** Optional. */
    @Column(precision = 14, scale = 2)
    private BigDecimal monthlySalary;

    @ElementCollection
    @CollectionTable(name = "offer_letter_lines", joinColumns = @JoinColumn(name = "offer_letter_id"))
    @OrderColumn(name = "line_order")
    private List<Line> lines = new ArrayList<>();

    @Column(length = 20)
    private String contactNumber;

    @Column(length = 150)
    private String contactEmail;

    /** The clauses and rules about joining, one per line. */
    @Column(length = 8000)
    private String clauses;

    /** Who signs it, as printed. */
    @Column(length = 150)
    private String issuedByName;

    @Column(length = 150)
    private String issuedByDesignation;

    @Column(length = 150)
    private String createdByName;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant issuedAt;

    /** The last change after it was issued. */
    @Column(length = 150)
    private String changedByName;

    private Instant changedAt;

    /** One part of the package split, a year's amount. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @Column(name = "name", nullable = false, length = 100)
        private String name;

        @Column(name = "annual_amount", nullable = false, precision = 14, scale = 2)
        private BigDecimal annualAmount;

        public Line(String name, BigDecimal annualAmount) {
            this.name = name;
            this.annualAmount = annualAmount;
        }
    }
}
