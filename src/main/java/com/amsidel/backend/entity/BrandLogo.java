package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A brand's logo (PNG or JPEG), kept apart from {@link Brand} so listing brands never loads image bytes. */
@Entity
@Table(name = "brand_logos")
@Getter
@Setter
@NoArgsConstructor
public class BrandLogo {

    /** Same value as brands.id. */
    @Id
    private Long brandId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private Instant updatedAt;
}
