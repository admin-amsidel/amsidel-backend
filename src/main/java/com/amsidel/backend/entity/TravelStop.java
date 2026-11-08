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

/** A point of a GPS trip, in order: where it started, each stop on the way, and where it ended. */
@Entity
@Table(name = "travel_stops")
@Getter
@Setter
@NoArgsConstructor
public class TravelStop {

    public enum Kind { START, STOP, END }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private TravelClaim claim;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** What the place is, e.g. the customer's office. */
    @Column(length = 200)
    private String label;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** Metres, as the phone reported it. */
    private Double accuracy;

    @Column(nullable = false)
    private Instant at;

    // ---- a location changed by hand (the Change stop locations tick): the GPS one is kept ----
    private Double originalLatitude;

    private Double originalLongitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edited_by")
    private Employee editedBy;

    private Instant editedAt;
}
