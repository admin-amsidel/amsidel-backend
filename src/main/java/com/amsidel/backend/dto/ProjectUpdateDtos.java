/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Project status updates for customers, and the customer's status link. */
public final class ProjectUpdateDtos {

    private ProjectUpdateDtos() {
    }

    @Data
    public static class UpdateBody {
        /** The stage: a project status. */
        @NotBlank(message = "Choose the stage")
        @Size(max = 30)
        private String status;

        @NotBlank(message = "Give the update a title")
        @Size(max = 150)
        private String title;

        @Size(max = 2000)
        private String description;

        /** When it happened; empty = now. */
        private Instant happenedAt;
    }

    /** One update as the portal shows it: who posted and changed it, and its photos (ids). */
    public record UpdateView(Long id, Long projectId, String status, String title, String description, Instant happenedAt,
                             List<Long> photoIds, String createdByName, Instant createdAt, String updatedByName, Instant updatedAt,
                             boolean canEdit) {
    }

    /** A project on the Project status page. */
    public record StatusRow(Long id, String code, String name, String brandCode, Long customerId, String customerName, String customerPhone,
                            String status, boolean settled, int updates, Instant lastUpdateAt, boolean shared) {
    }

    /** A project's updates, the link for its customer (null = not shared), whether I may post, and the update just posted or changed. */
    public record ProjectStatusView(StatusRow project, List<UpdateView> updates, String sharePath, boolean canPost, Long touchedId) {
    }

    // ---- the customer's link (no sign-in) ----

    public record PublicUpdate(Long id, String status, String title, String description, Instant happenedAt, List<Long> photoIds) {
    }

    /** Only what the customer needs: no amounts, no staff names. */
    public record PublicStatus(String brandName, Long brandId, Instant brandLogoUpdatedAt, String brandPhone, String brandEmail,
                               String projectCode, String projectName, String customerName, String status,
                               LocalDate startDate, LocalDate endDate, List<PublicUpdate> updates,
                               List<com.amsidel.backend.service.ProjectStageService.StageView> stages) {
    }
}
