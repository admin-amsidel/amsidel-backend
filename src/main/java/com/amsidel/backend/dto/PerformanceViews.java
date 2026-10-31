/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the performance module returns (/api/performance). */
public final class PerformanceViews {

    private PerformanceViews() {
    }

    public record CycleView(Long id, String name, LocalDate startDate, LocalDate endDate, Long brandId, String brandName,
                            boolean closed) {
        /** Call inside a transaction: reads the brand. */
        public static CycleView of(ReviewCycle c) {
            return new CycleView(c.getId(), c.getName(), c.getStartDate(), c.getEndDate(),
                    c.getBrand() == null ? null : c.getBrand().getId(), c.getBrand() == null ? null : c.getBrand().getName(),
                    c.isClosed());
        }
    }

    public record Person(Long id, String name, String empNumber, String designationName, String photoPath, Instant photoUpdatedAt) {
        public static Person of(Employee e) {
            return new Person(e.getId(), e.getName(), e.getEmpNumber(),
                    e.getDesignation() == null ? null : e.getDesignation().getName(), e.getPhotoPath(), e.getPhotoUpdatedAt());
        }
    }

    /** What the viewer may do: as the person (self) or as their manager. Both false when the cycle is closed. */
    /** admin: an owner or admin over this person, who may edit any goal (rated, done, past its end or in a closed cycle). */
    public record Rights(boolean self, boolean manager, boolean admin) {
        public Rights(boolean self, boolean manager) {
            this(self, manager, false);
        }
    }

    /** selfLevel 0: the person has not given their level yet; addedBy: the manager who added it (null = the person). */
    public record SkillView(Long id, String name, SkillCategory category, BigDecimal selfLevel, BigDecimal managerLevel,
                            String managerNote, String validatedBy, Instant validatedAt, String addedBy) {
        public static SkillView of(Skill s) {
            return new SkillView(s.getId(), s.getName(), s.getCategory(), s.getSelfLevel(), s.getManagerLevel(),
                    s.getManagerNote(), s.getValidatedBy() == null ? null : s.getValidatedBy().getName(), s.getValidatedAt(),
                    s.getAddedBy() == null ? null : s.getAddedBy().getName());
        }
    }

    /** A sub-task; for a sales goal also its target and what was achieved towards it (progress follows the two). */
    public record TaskView(Long id, String title, int weightage, LocalDate startDate, LocalDate endDate, int progress,
                           BigDecimal targetAmount, BigDecimal achievedAmount) {
        public static TaskView of(GoalTask t) {
            return new TaskView(t.getId(), t.getTitle(), t.getWeightage(), t.getStartDate(), t.getEndDate(), t.getProgress(), null, null);
        }
    }

    /**
     * One confirmed sales order counted in a sales-order goal: its order date (confirmedOn), its value before GST (what counts)
     * and with GST, the customer and project, and the invoice made from it (if any).
     */
    public record OrderLineView(Long id, String number, LocalDate confirmedOn, String month, BigDecimal amount, BigDecimal total,
                                String customerName, String projectCode, Long invoiceId, String invoiceNumber, String invoiceStatus) {
    }

    /** One record of what was achieved towards a sales goal. */
    public record SalesEntryView(Long id, Long taskId, String taskTitle, BigDecimal amount, LocalDate fromDate, LocalDate toDate,
                                 List<String> invoiceNumbers, String description, Instant createdAt) {
        public static SalesEntryView of(SalesEntry e) {
            List<String> invoices = e.getInvoiceNumbers() == null ? List.of()
                    : java.util.Arrays.stream(e.getInvoiceNumbers().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            return new SalesEntryView(e.getId(), e.getTask() == null ? null : e.getTask().getId(),
                    e.getTask() == null ? null : e.getTask().getTitle(), e.getAmount(), e.getFromDate(), e.getToDate(), invoices,
                    e.getDescription(), e.getCreatedAt());
        }
    }

    /**
     * A goal with its sub-tasks; progress is worked out from the sub-tasks (weight x % done), or the goal's own %
     * when it has none. canSelfRate / canManagerRate say whether that rating can be given now.
     * A sales goal (type SALES) also has its target, the amount achieved (its entries added up), achievedPercent (can
     * pass 100) and the entries; its progress and self rating follow from them. canRecord: the person may add entries.
     */
    public record GoalView(Long id, String title, String description, int weightage, LocalDate startDate, LocalDate endDate,
                           BigDecimal progress, List<TaskView> tasks, BigDecimal selfRating, String selfComment,
                           Instant selfRatedAt, BigDecimal managerRating, String managerComment, String managerRatedBy,
                           Instant managerRatedAt, boolean canSelfRate, boolean canManagerRate, boolean canEdit,
                           GoalType type, BigDecimal targetAmount, BigDecimal achievedAmount, BigDecimal achievedPercent,
                           List<SalesEntryView> salesEntries, boolean canRecord, List<OrderLineView> orders) {
    }

    public record AchievementView(Long id, String title, String category, LocalDate achievedOn, String details,
                                  String issuedBy, boolean selfReported) {
        public static AchievementView of(Achievement a) {
            return new AchievementView(a.getId(), a.getTitle(), a.getCategory(), a.getAchievedOn(), a.getDetails(),
                    a.getIssuedBy() == null ? null : a.getIssuedBy().getName(), a.getIssuedBy() == null);
        }
    }

    /**
     * Totals: goal weights (should be 100), overall % done (weight x goal %), and the weighted average ratings
     * over the goals rated so far (null when none).
     */
    public record Totals(int weightTotal, BigDecimal progress, BigDecimal selfRating, BigDecimal managerRating,
                         int goals, int selfRated, int managerRated) {
    }

    /** One person's review in one cycle. */
    /** currencySymbol: the person's brand currency, for sales targets and amounts. */
    public record ReviewView(CycleView cycle, Person employee, Rights rights, String selfIntro, List<SkillView> skills,
                             List<GoalView> goals, List<AchievementView> achievements, Totals totals, LocalDate today,
                             String currencySymbol) {
    }

    /** One row of the team overview. */
    public record TeamRow(Person employee, Totals totals, int skills, int skillsToValidate, int toRate) {
    }

    /** 1 decimal place. */
    public static BigDecimal one(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }
}
