/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.LeaveAllocation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Leave plans: one brand's leave for a period (/api/leave/plans). */
public final class LeavePlanDtos {

    private LeavePlanDtos() {
    }

    @Data
    public static class PlanBody {
        /** Empty = the group-level people, who have no brand. */
        private Long brandId;
        @NotNull(message = "Give the date the plan is valid from")
        private LocalDate validFrom;
        @NotNull(message = "Give the date the plan is valid until")
        private LocalDate validUntil;
        @Valid
        @NotEmpty(message = "Add at least one type of leave")
        private List<LineBody> lines;
    }

    @Data
    public static class LineBody {
        @NotNull
        private Long leaveTypeId;
        @Min(value = 0, message = "Days cannot be negative")
        @Max(value = 365, message = "At most 365 days")
        private Integer days;
        private boolean carryForward;
        @DecimalMin(value = "0", message = "The carry forward limit cannot be negative")
        private BigDecimal maxCarryDays;
    }

    public record LineView(Long leaveTypeId, String code, String name, LeaveAllocation allocation, Integer days,
                           boolean carryForward, BigDecimal maxCarryDays) {
    }

    /** state: CURRENT, FUTURE or ENDED; daysLeft: until it ends, for a current one. */
    public record PlanView(Long id, Long brandId, String brandCode, String brandName, LocalDate validFrom, LocalDate validUntil,
                           String state, Long daysLeft, boolean editable, boolean removable, List<LineView> lines) {
    }

    /**
     * A brand with its plans. canAdd: a new plan can be added now; addOpensOn: else the day it can (10 days before the
     * current plan ends), null when one is already waiting.
     */
    public record BrandPlans(Long brandId, String brandCode, String brandName, boolean canAdd, LocalDate addOpensOn,
                             LocalDate nextFrom, List<PlanView> plans) {
    }

    public record Overview(LocalDate today, List<BrandPlans> brands) {
    }

    /** Needs an owner's or admin's attention: a plan ending within 10 days with no next one, or a brand with no plan. */
    public record Alert(Long brandId, String brandCode, String brandName, LocalDate validUntil, Long daysLeft) {
    }

    /** What the person sees: the plan in force today (null fields when there is none) and their balances in it. */
    public record MyCurrent(Long planId, String brandCode, String brandName, LocalDate validFrom, LocalDate validUntil,
                            LocalDate today, List<LeaveBalance> balances) {
    }
}
