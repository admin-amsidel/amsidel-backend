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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One brand's leave for a period: valid from and until, and which types of leave it has with how many days. A brand's
 * plans never overlap; the one covering today is what its people see and apply against.
 */
@Entity
@Table(name = "leave_plans")
@Getter
@Setter
@NoArgsConstructor
public class LeavePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = the group-level people, who have no brand. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    /** When owners and admins were told it is about to end; null = not yet. */
    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<LeavePlanLine> lines = new ArrayList<>();

    public boolean covers(LocalDate day) {
        return !day.isBefore(validFrom) && !day.isAfter(validUntil);
    }
}
