/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.SkillCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Request bodies of the performance module (/api/performance). */
public final class PerformanceRequests {

    private PerformanceRequests() {
    }

    @Data
    public static class Cycle {
        @NotBlank @Size(max = 80)
        private String name;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
        /** Empty = every brand. */
        private Long brandId;
        private boolean closed;
    }

    @Data
    public static class Intro {
        @Size(max = 4000)
        private String selfIntro;
    }

    @Data
    public static class SkillBody {
        @NotBlank @Size(max = 100)
        private String name;
        @NotNull
        private SkillCategory category;
        /** 0.5-5 in half stars. */
        @NotNull @DecimalMin("0.5") @DecimalMax("5.0")
        private BigDecimal selfLevel;
    }

    /** A skill a manager adds to someone's review, with their level and note. */
    @Data
    public static class ManagerSkill {
        @NotBlank @Size(max = 100)
        private String name;
        @NotNull
        private SkillCategory category;
        @NotNull @DecimalMin("0.5") @DecimalMax("5.0")
        private BigDecimal managerLevel;
        @Size(max = 500)
        private String note;
    }

    @Data
    public static class SkillValidation {
        @NotNull @DecimalMin("0.5") @DecimalMax("5.0")
        private BigDecimal managerLevel;
        @Size(max = 500)
        private String note;
    }

    @Data
    public static class GoalBody {
        @NotBlank @Size(max = 150)
        private String title;
        /** GENERAL (default) or SALES. */
        private com.amsidel.backend.entity.GoalType type;
        /** Sales goals: the amount to achieve. */
        @DecimalMin(value = "0.01", message = "The target amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal targetAmount;
        @Size(max = 2000)
        private String description;
        @NotNull @Min(1) @Max(100)
        private Integer weightage;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
        /** Sub-tasks in order; their weights must add up to 100 (or no sub-tasks). */
        @Valid @Size(max = 30)
        private List<TaskBody> tasks = new ArrayList<>();
    }

    @Data
    public static class TaskBody {
        /** Existing sub-task (keeps its progress); empty for a new one. */
        private Long id;
        @NotBlank @Size(max = 150)
        private String title;
        /** General goals: % of the goal (sales goals: worked out from the target amounts). */
        @Min(1) @Max(100)
        private Integer weightage;
        /** Sales goals: this sub-task's target amount. */
        @DecimalMin(value = "0.01", message = "A sub-task's target must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal targetAmount;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
    }

    @Data
    public static class Progress {
        @NotNull @Min(0) @Max(100)
        private Integer progress;
    }

    @Data
    public static class Rating {
        /** 0.5-5 in half stars. */
        @NotNull @DecimalMin("0.5") @DecimalMax("5.0")
        private BigDecimal rating;
        @Size(max = 2000)
        private String comment;
    }

    /** What the person achieved towards a sales goal: amount, period, invoice numbers and a note. */
    @Data
    public static class SalesEntryBody {
        /** The sub-task it counts for (needed when the goal has sub-tasks). */
        private Long taskId;
        @NotNull(message = "Give the amount achieved")
        @DecimalMin(value = "0.01", message = "The amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;
        @NotNull(message = "Give the start date")
        private LocalDate fromDate;
        @NotNull(message = "Give the end date")
        private LocalDate toDate;
        /** Comma separated: "INV-101, INV-102". */
        @Size(max = 1000)
        private String invoiceNumbers;
        @Size(max = 1000)
        private String description;
    }

    @Data
    public static class AchievementBody {
        /** Someone below me (issued by me); empty = my own, self-reported. */
        private Long employeeId;
        @NotBlank @Size(max = 120)
        private String title;
        @Size(max = 60)
        private String category;
        @NotNull
        private LocalDate achievedOn;
        @Size(max = 2000)
        private String details;
    }
}
