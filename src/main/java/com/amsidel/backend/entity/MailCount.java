/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** How many emails of one kind went to one brand's people on one day (in the office time zone). */
@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor
public abstract class MailCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The brand's id; 0 for the group-level people, who have no brand. */
    @Column(name = "brand_key", nullable = false)
    private Long brandKey;

    @Column(name = "sent_on", nullable = false)
    private LocalDate sentOn;

    @Column(name = "sent_count", nullable = false)
    private int sentCount;
}
