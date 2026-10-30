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

/** A news item's picture (JPEG, PNG or WebP), kept apart so listing news never loads image bytes. */
@Entity
@Table(name = "news_images")
@Getter
@Setter
@NoArgsConstructor
public class NewsImage {

    /** Same value as news_posts.id. */
    @Id
    private Long newsId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private Instant updatedAt;
}
