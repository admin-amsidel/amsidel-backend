/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.NewsAudience;
import com.amsidel.backend.entity.NewsCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** News and announcements: the dashboard slider, the website's offers, and managing them. */
public final class NewsDtos {

    private NewsDtos() {
    }

    @Data
    public static class NewsBody {
        @NotBlank(message = "Give the heading")
        @Size(max = 150, message = "Keep the heading to 150 characters")
        private String title;

        @Size(max = 4000, message = "Keep the text to 4000 characters")
        private String body;

        @NotNull(message = "Choose what it is about")
        private NewsCategory category;

        @NotNull(message = "Choose who it is for")
        private NewsAudience audience;

        /** null = the whole group. */
        private Long brandId;

        /** null = today. */
        private LocalDate startDate;

        /** null = until hidden. */
        private LocalDate endDate;

        private boolean active = true;

        private boolean pinned;
    }

    /** status: LIVE (showing today), SCHEDULED (starts later), ENDED, HIDDEN (not active). */
    public record NewsView(Long id, String title, String body, NewsCategory category, NewsAudience audience, Long brandId,
                           String brandCode, String brandName, LocalDate startDate, LocalDate endDate, boolean active,
                           boolean pinned, Instant imageUpdatedAt, String status, String createdByName, Instant createdAt,
                           String updatedByName, Instant updatedAt) {
    }

    /** Someone whose birthday is today. */
    public record Birthday(Long id, String name, String designation, String brandCode, Instant photoUpdatedAt) {
    }

    /** The dashboard: today's news, and today's birthdays (from the employees' dates of birth). */
    public record Feed(List<NewsView> news, List<Birthday> birthdays, boolean canManage) {
    }

    /** For the website: no names of who wrote it. */
    public record PublicNews(Long id, String title, String body, NewsCategory category, String brandCode, String brandName,
                             LocalDate startDate, LocalDate endDate, Instant imageUpdatedAt) {
    }
}
