/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.AgentCommission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Agents who bring in projects, and their commissions. */
public final class AgentDtos {

    private AgentDtos() {
    }

    public record Body(
            Long brandId,
            @NotBlank(message = "Give the agent's name") @Size(max = 150, message = "The name can be at most 150 characters") String name,
            @NotBlank(message = "Give the agent's phone") String phone,
            @Size(max = 500, message = "The address can be at most 500 characters") String address,
            @Size(max = 100, message = "The location can be at most 100 characters") String location,
            @Size(max = 80) @Pattern(regexp = "|[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}", message = "UPI ID looks like name@bank, e.g. anu@okhdfcbank") String upiId,
            @Size(max = 500, message = "The notes can be at most 500 characters") String notes,
            Boolean active) {
    }

    /** queued, settled: what they have earned, null for those without the Commission details tick. */
    public record Row(Long id, String code, Long brandId, String brandCode, String brandName, String name, String phone, String address, String location,
                      String upiId, String notes, boolean active, Instant photoUpdatedAt, Integer projects, BigDecimal queued, BigDecimal settled, String currencySymbol) {
    }

    /** What the project form needs to choose an agent: nothing about their money. */
    public record Option(Long id, String code, String name, String phone, Long brandId, boolean active) {
    }

    public record BrandOption(Long id, String code, String name) {
    }

    /** One commission line. customerPaid, projectValue: what the customer has paid on the project and what they are to pay, as on the project's page. */
    public record Commission(Long id, Long agentId, String agentCode, String agentName, String agentPhone, String agentUpi, Long projectId, String projectCode,
                             String projectName, String customerName, Long brandId, String brandCode, String currencySymbol, BigDecimal amount, LocalDate completedOn,
                             String remarks, AgentCommission.Status status, String transactionId, LocalDate paidOn, String paymentRemarks,
                             String statusChangedByName, Instant statusChangedAt, BigDecimal customerPaid, BigDecimal projectValue) {
    }

    /** canChangeStatus: this person has the status tick. */
    public record CommissionPage(boolean canChangeStatus, List<Commission> rows) {
    }

    /** Settled needs the payment's date and transaction ID; back to Queued clears them. */
    public record StatusBody(
            @NotNull(message = "Choose the status") AgentCommission.Status status,
            @Size(max = 60, message = "The transaction ID can be at most 60 characters") String transactionId,
            LocalDate paidOn,
            @Size(max = 500, message = "The remarks can be at most 500 characters") String remarks) {
    }
}
