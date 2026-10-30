/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A brand's UPI payment QR code as uploaded (e.g. the PhonePe / GPay business QR), printed on its invoices; kept
 * apart from {@link Brand} so listing brands never loads image bytes. payload = what the QR says (upi://pay?pa=…).
 */
@Entity
@Table(name = "brand_upi_qrs")
@Getter
@Setter
@NoArgsConstructor
public class BrandUpiQr {

    /** Same value as brands.id. */
    @Id
    private Long brandId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(length = 1000)
    private String payload;

    @Column(nullable = false)
    private Instant updatedAt;
}
