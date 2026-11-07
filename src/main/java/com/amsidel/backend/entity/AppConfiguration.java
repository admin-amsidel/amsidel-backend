/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One setting that can be changed without a release. While it is active its value replaces the one in the properties
 * file; when it is inactive, empty or missing, the properties file's value applies (see ConfigValues).
 */
@Entity
@Table(name = "configurations")
@Getter
@Setter
@NoArgsConstructor
public class AppConfiguration {

    @Id
    @Column(length = 60)
    private String code;

    @Column(length = 300)
    private String description;

    @Column(name = "config_value", length = 500)
    private String value;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Who last changed it on the Configuration page, and when; null for a row made at start-up. */
    @Column(length = 150)
    private String updatedByName;

    private java.time.Instant updatedAt;
}
