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

import java.time.Instant;

/**
 * Someone a brand sells to; the first phone number is unique in the brand.
 */
@Entity
@Table(name = "customers",
        uniqueConstraints = {@UniqueConstraint(name = "uk_customer_brand_phone", columnNames = {"brand_id", "phone"}), @UniqueConstraint(name = "uk_customer_brand_code", columnNames = {"brand_id", "code"})})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** Customer number, e.g. DWC-0012. */
    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 20)
    private String phone2;

    @Column(length = 150)
    private String email;

    @Column(length = 100)
    private String location;

    @Column(length = 500)
    private String address;

    @Column(length = 15)
    private String gstNumber;

    @Column(length = 10)
    private String panNumber;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    /** Who last changed it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
