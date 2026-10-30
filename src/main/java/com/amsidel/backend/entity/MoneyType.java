/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A kind of other income or expense (Income & expenses), set up by owners and admins: money in or out; funding
 * (investment, loans and their repayment) moves the bank balance but is not profit or loss. The ones the portal
 * started with (MoneyCategory) keep their codes; DISCOUNT is the invoice write-off and stays as it is.
 */
@Entity
@Table(name = "money_types")
@Getter
@Setter
@NoArgsConstructor
public class MoneyType {

    /** The discount allowed on an invoice (a write-off when no money moved). */
    public static final String DISCOUNT = "DISCOUNT";

    @Id
    @Column(length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String label;

    /** Money in (else money out). */
    @Column(nullable = false)
    private boolean income;

    /** Investment, loans and repayments: in the bank tally, not in the profit and loss. */
    @Column(nullable = false)
    private boolean funding;

    /** Inactive: kept for the entries made with it, not offered for new ones. */
    @Column(nullable = false)
    private boolean active = true;

    /** One the portal started with. */
    @Column(nullable = false)
    private boolean builtIn;

    @Column(nullable = false)
    private int sortOrder;

    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    public boolean isDiscount() {
        return DISCOUNT.equals(code);
    }
}
