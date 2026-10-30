/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.dto.PayrollViews.Person;
import com.amsidel.backend.dto.PayrollViews.SalaryView;
import com.amsidel.backend.entity.PayComponentKind;
import com.amsidel.backend.entity.PaymentMode;
import com.amsidel.backend.entity.PayslipStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Monthly payslips: what payroll and the employee screens send and get. Money in rupees with 2 decimals. */
public final class PayslipDtos {

    private PayslipDtos() {
    }

    // ---- requests ----

    /** Make draft payslips for a month: everyone with a salary that month, or only these people. */
    @Data
    public static class Generate {
        @Min(2000)
        @Max(2100)
        private int year;

        @Min(1)
        @Max(12)
        private int month;

        private List<Long> employeeIds;

        /** This month's working days for everyone made now; empty = each salary's own (default 26). */
        @Min(1)
        @Max(31)
        private Integer workingDays;
    }

    /** Save a payslip: as a draft, or publish it (publish = true) so the employee sees it. */
    @Data
    public static class Save {
        @NotNull
        private LocalDate payDate;

        @Min(0)
        @Max(31)
        private int lopDays;

        /** Working days in this month; empty = as it is. */
        @Min(value = 1, message = "Working days: 1 to 31")
        @Max(value = 31, message = "Working days: 1 to 31")
        private Integer workingDays;

        @NotNull
        @Size(max = 60)
        private List<@Valid LineAmount> lines;

        private boolean publish;
    }

    @Data
    public static class LineAmount {
        @NotNull
        private Long id;

        @NotNull
        @DecimalMin(value = "0", message = "Amounts cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "At most 2 decimals")
        private BigDecimal amount;
    }

    /** The salary was credited: when, how, the transaction ID (UTR, UPI or cheque number) and remarks; both optional. */
    @Data
    public static class Payment {
        @NotNull
        private LocalDate paidOn;

        @NotNull
        private PaymentMode mode;

        @Size(max = 60, message = "Transaction ID: at most 60 characters")
        private String reference;

        @Size(max = 500, message = "Remarks: at most 500 characters")
        private String remarks;
    }

    /** Several payslips credited the same day the same way. */
    @Data
    public static class BulkPayment {
        @NotNull
        @Size(min = 1, max = 500)
        private List<Long> ids;

        @NotNull
        private LocalDate paidOn;

        @NotNull
        private PaymentMode mode;

        /** One transaction for all of them (e.g. a bank batch); optional. */
        @Size(max = 60, message = "Transaction ID: at most 60 characters")
        private String reference;

        @Size(max = 500, message = "Remarks: at most 500 characters")
        private String remarks;
    }

    @Data
    public static class Ids {
        @NotNull
        @Size(min = 1, max = 500)
        private List<Long> ids;
    }

    // ---- views ----

    /** The employee's brand as the payslip shows it; logo at /api/brands/{brandId}/logo when logoUpdatedAt is set. */
    /** currency: the brand's (INR, USD, EUR, GBP) for the payslip's amounts. */
    public record Company(String name, String address, String gstNumber, Long brandId, Instant logoUpdatedAt, String phone,
                          String email, String currency, String currencySymbol) {
    }

    public record Money(BigDecimal gross, BigDecimal deductions, BigDecimal reimbursements, BigDecimal net) {
    }

    /** One person in the month's list: salary set this month or not, and their payslip if there is one. */
    /** paidOn / paymentMode: when and how it was credited (null = not yet). */
    public record Row(Person employee, boolean salarySet, Long payslipId, PayslipStatus status, int workingDays, int paidDays,
                      int lopDays, Money money, boolean editable, LocalDate paidOn, PaymentMode paymentMode) {
    }

    /** published counts all published payslips; credited those of them also credited. */
    public record MonthView(int year, int month, String label, int fyStartYear, List<Row> rows, int eligible, int drafts,
                            int published, int credited, Money total) {
    }

    public record LineView(Long id, String code, String name, PayComponentKind kind, boolean tax, BigDecimal fullAmount,
                           BigDecimal amount, BigDecimal ytd) {
    }

    public record PayslipView(Long id, Person employee, String designationName, int year, int month, String periodLabel,
                              int fyStartYear, PayslipStatus status, LocalDate payDate, int daysInMonth, int workingDays,
                              int employedDays,
                              int lopDays, int paidDays, List<LineView> lines, Money money, String netInWords,
                              Company company, boolean editable, String publishedByName, Instant publishedAt,
                              Instant updatedAt, LocalDate paidOn, PaymentMode paymentMode, String paymentRef,
                              String paymentRemarks, String paidRecordedByName, PayslipTax tax) {
    }

    /**
     * This month's income tax under the salary's regime (New or Old): the year's income expected (earlier months as
     * paid, this month, the months to come at the salary's rates), its tax, the tax deducted in the earlier months, and
     * what is left spread over this and the months to come.
     */
    public record PayslipTax(com.amsidel.backend.entity.TaxRegime regime, String rules, BigDecimal yearIncome, BigDecimal yearTax,
                             BigDecimal taxBefore, int monthsLeft, BigDecimal monthTax) {
    }

    /** The payslip's amounts being typed, for this month's income tax before saving. */
    @Data
    public static class TaxPreview {
        @NotNull
        @Size(max = 60)
        private List<@Valid LineAmount> lines;
    }

    public record GenerateResult(int created, int skipped, List<String> notes) {
    }

    // ---- the employee's own pay ----

    /**
     * expected*: what the salary pays in the year (from its start date to March); earned/received/taxesPaid: from
     * published payslips; balance: expected gross not yet paid.
     */
    public record Dashboard(BigDecimal expectedGross, BigDecimal expectedNet, BigDecimal expectedTax, BigDecimal earnedGross,
                            BigDecimal receivedNet, BigDecimal taxesPaid, BigDecimal balanceGross, int monthsPaid,
                            int monthsInYear) {
    }

    /** One month of the year: its published payslip, if any. */
    public record MonthPay(int year, int month, String label, Long payslipId, BigDecimal gross, BigDecimal net, BigDecimal tax) {
    }

    public record Summary(Long id, int year, int month, String label, LocalDate payDate, int paidDays, int lopDays, Money money,
                          LocalDate paidOn, PaymentMode paymentMode) {
    }

    public record MyPay(int fyStartYear, String fyLabel, List<Integer> years, SalaryView salary, Dashboard dashboard,
                        List<MonthPay> months, List<Summary> payslips) {
    }
}
