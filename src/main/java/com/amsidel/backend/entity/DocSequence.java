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

/** The last number used for a prefix such as DWSLD-102026 (brand, kind, month): bill numbers run on from it. */
@Entity
@Table(name = "doc_sequences")
@Getter
@Setter
@NoArgsConstructor
public class DocSequence {

    @Id
    @Column(length = 30)
    private String prefix;

    @Column(nullable = false)
    private int lastNumber;
}
