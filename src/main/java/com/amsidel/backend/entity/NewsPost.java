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
import java.time.LocalDate;

/**
 * A news item for the dashboard slider (and, for customers, the website): a heading, the text and a picture, about a
 * discount, a promotion, a birthday, an important notice… It shows from its start date to its end date (none = until
 * hidden) while active; pinned ones come first. For the whole group, or one brand.
 */
@Entity
@Table(name = "news_posts", indexes = @Index(name = "ix_news_dates", columnList = "start_date, end_date"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class NewsPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NewsCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private NewsAudience audience;

    /** null = the whole group. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** The last day it shows; null = until hidden. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean pinned;

    /** null = no picture. */
    private Instant imageUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Showing on this day. */
    public boolean liveOn(LocalDate day) {
        return active && !startDate.isAfter(day) && (endDate == null || !endDate.isBefore(day));
    }
}
