/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.ApprovalStatus;
import com.amsidel.backend.entity.ManualSalesKind;
import com.amsidel.backend.entity.TaskStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The Manual Sales worklog: tasks, what a work log entry records, and the figures. */
public final class ManualSalesDtos {

    private ManualSalesDtos() {
    }

    // ---- tasks ----

    @Data
    public static class TaskBody {
        @NotBlank(message = "Give the task a title")
        @Size(max = 200)
        private String title;
        @Size(max = 2000)
        private String description;
        private TaskStatus status;
        private Long brandId;
        @Size(max = 150)
        private String customerName;
        @Size(max = 30)
        private String customerPhone;
        @Size(max = 150)
        private String projectName;
        @Size(max = 150)
        private String agentName;
        @Size(max = 30)
        private String agentPhone;
        private List<Long> assigneeIds = new ArrayList<>();
    }

    public record Assignee(Long id, String name) {
    }

    /** ordered, invoiced and quoted: approved entries only. balance: ordered less invoiced; null when filtered to part of the task's entries. */
    public record TaskView(Long id, String code, String title, String description, TaskStatus status, Long brandId, String brandCode, String currencySymbol,
                           String customerName, String customerPhone, String projectName, String agentName, String agentPhone, List<Assignee> assignees,
                           String latestStage, BigDecimal quoted, BigDecimal ordered, BigDecimal invoiced, BigDecimal balance, String createdByName, LocalDate createdOn) {
    }

    /**
     * The filters of the tasks page and its export. Dates: a task created or worked on in them. Employee: a task given to them
     * or logged by them. With either, the amounts count only those dates and that person's entries.
     */
    public record TaskFilter(TaskStatus status, LocalDate from, LocalDate to, String code, Long employeeId, String q) {
    }

    public record PersonOption(Long id, String name, String empNumber, Long brandId, String brandCode) {
    }

    public record BrandOption(Long id, String code, String name) {
    }

    /** For the task form: who can be assigned, and the names used before, to pick from. */
    public record TaskOptions(List<PersonOption> people, List<BrandOption> brands, List<String> customers, List<String> projects, List<String> agents,
                              List<String> taskCodes, List<PersonOption> everyone) {
    }

    // ---- a work log entry ----

    /** kind: which details the status asks for; null for a status with none. */
    public record StageOption(String code, String name, ManualSalesKind kind) {
    }

    /** What a work log entry on a Manual Sales task adds; number, date and amount for a quotation, sales order or invoice. */
    @Data
    public static class EntryBody {
        @NotBlank(message = "Choose the status of the task")
        @Size(max = 30)
        private String stageCode;
        @Size(max = 60, message = "The number can be at most 60 characters")
        private String documentNumber;
        private LocalDate documentDate;
        @DecimalMin(value = "0.01", message = "The amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;
    }

    public record EntryView(String stageCode, String stageName, ManualSalesKind kind, String kindLabel, String documentNumber, LocalDate documentDate, BigDecimal amount,
                            String currencySymbol, String customerName, String projectName, String agentName) {
    }

    // ---- the figures ----

    public record Figure(long count, BigDecimal amount) {
    }

    /** One week, month or financial year of approved entries, by their work log day. */
    public record Bucket(String label, LocalDate from, LocalDate to, Figure quotations, Figure salesOrders, Figure invoices) {
    }

    /** Amounts in different currencies are never added together: one of these per currency. */
    public record CurrencyFigures(String symbol, List<Bucket> weeks, List<Bucket> months, List<Bucket> years) {
    }

    public record Summary(LocalDate today, List<CurrencyFigures> currencies) {
    }

    // ---- the details page ----

    public record Row(Long id, LocalDate workDate, Long employeeId, String employeeName, String empNumber, String brandCode, String taskCode, String taskTitle,
                      String customerName, String projectName, String agentName, String stageName, ManualSalesKind kind, String kindLabel, String documentNumber,
                      LocalDate documentDate, BigDecimal amount, String currencySymbol, ApprovalStatus approval, String decidedBy, Instant decidedAt, String description) {
    }

    /** Approved rows only, whatever the approval filter shows. */
    public record Totals(String symbol, Figure quotations, Figure salesOrders, Figure invoices) {
    }

    /** truncated: there were more rows than the limit: narrow the filters. */
    public record Details(LocalDate from, LocalDate to, List<Row> rows, List<Totals> totals, boolean truncated) {
    }

    public record DetailOptions(List<PersonOption> people, List<BrandOption> brands, List<StageOption> stages, List<String> customers, List<String> projects, List<String> agents,
                                LocalDate defaultFrom, LocalDate defaultTo) {
    }

    /** The filters of the details page. */
    public record Filter(LocalDate from, LocalDate to, Long employeeId, Long brandId, String customer, String project, String agent, ManualSalesKind kind, String stageCode,
                         ApprovalStatus approval) {
    }

    /** A line of a Manual Sales worklog goal. */
    public record GoalLine(Long id, LocalDate workDate, String month, ManualSalesKind kind, String kindLabel, String documentNumber, LocalDate documentDate, BigDecimal amount,
                           String taskCode, String customerName, String projectName, String agentName) {
    }
}
