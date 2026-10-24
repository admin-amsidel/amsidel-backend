package com.amsidel.backend.dto;

import com.amsidel.backend.dto.TradeDtos.HistoryView;
import com.amsidel.backend.entity.MoneyCategory;
import com.amsidel.backend.entity.TradePayMode;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The product portal's accounts: other income and expenses, bank statements and balances, and the P&L reconciler
 * (profit and loss, the bank tally, bank lines matched to the portal's money, and invoices paid short).
 */
public final class AccountDtos {

    private AccountDtos() {
    }

    // ---- other income and expenses ----

    @Data
    public static class EntryBody {
        private Long brandId;

        @NotNull(message = "Choose what it is for")
        private MoneyCategory category;

        @NotNull(message = "Give the date")
        private LocalDate entryDate;

        @NotNull(message = "Give the amount")
        @DecimalMin(value = "0.01", message = "Amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;

        /** null = no money moved (only for a discount written off on an invoice). */
        private TradePayMode mode;

        @Size(max = 150)
        private String partyName;

        @Size(max = 80)
        private String transactionId;

        @NotBlank(message = "Say what it was")
        @Size(max = 300)
        private String description;

        @Size(max = 1000)
        private String remarks;

        private Long invoiceId;
        private Long projectId;
        private Long purchaseBillId;
        private Long supplierId;
        private Long bankLineId;
    }

    /** What the customer did not pay on an invoice, kept as a discount (an expense); the invoice is then settled. */
    @Data
    public static class WriteOffBody {
        @NotNull(message = "Give the date")
        private LocalDate entryDate;

        /** null = all that is still due. */
        @DecimalMin(value = "0.01", message = "Amount must be more than 0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal amount;

        @Size(max = 300)
        private String description;

        @Size(max = 1000)
        private String remarks;
    }

    public record EntryView(Long id, String number, String brandCode, Long brandId, MoneyCategory category, String categoryLabel,
                            boolean income, boolean funding, LocalDate entryDate, BigDecimal amount, TradePayMode mode,
                            String partyName, String transactionId, String description, String remarks,
                            Long invoiceId, String invoiceNumber, Long projectId, String projectCode, String projectName,
                            Long customerId, String customerName, Long purchaseBillId, String purchaseBillNumber,
                            Long supplierId, String supplierName, Long bankLineId, boolean writeOff,
                            String createdByName, Instant createdAt, String updatedByName, Instant updatedAt,
                            boolean canDelete, List<HistoryView> history) {
    }

    public record InvoiceOption(Long id, String number, LocalDate date, Long customerId, String customerName, String customerPhone,
                                Long projectId, String projectCode, BigDecimal total, BigDecimal paid, BigDecimal writtenOff,
                                BigDecimal due) {
    }

    public record ProjectOption(Long id, String code, String name, Long customerId, String customerName, boolean settled) {
    }

    public record BillOption(Long id, String number, String supplierBillNo, Long supplierId, String supplierName, BigDecimal total) {
    }

    public record SupplierOption(Long id, String code, String name) {
    }

    /** What an entry can be linked to, in one brand. */
    public record EntryLinks(List<InvoiceOption> invoices, List<ProjectOption> projects, List<BillOption> purchases,
                             List<SupplierOption> suppliers) {
    }

    // ---- bank ----

    @Data
    public static class BalanceBody {
        private Long brandId;

        /** 2026-10: one balance per month. */
        @NotBlank(message = "Choose the month")
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "Choose the month")
        private String month;

        @NotNull(message = "Give the balance")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal balance;

        @Size(max = 300)
        private String note;
    }

    /** month: 2026-10; asOf: the day it counts for (the month's last day, or the day typed for this month). */
    public record BalanceView(Long id, String brandCode, String month, LocalDate asOf, BigDecimal balance, String note,
                              String enteredByName, Instant createdAt, String updatedByName, Instant updatedAt) {
    }

    public record StatementView(Long id, String brandCode, String fileName, LocalDate fromDate, LocalDate toDate,
                                BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal totalCredit,
                                BigDecimal totalDebit, int lines, String remarks, String uploadedByName, Instant uploadedAt,
                                List<String> warnings) {
    }

    // ---- the reconciler ----

    /** A sum by what it is: e.g. "Transportation" ₹4,200. */
    public record Part(String key, String label, BigDecimal amount) {
    }

    /** Money that moved in a period, all ways (bank, UPI, cash…). */
    public record Flow(BigDecimal customerReceipts, BigDecimal otherIncome, BigDecimal funding, BigDecimal moneyIn,
                       BigDecimal salaries, BigDecimal supplierPayments, BigDecimal otherExpenses, BigDecimal fundingOut,
                       BigDecimal moneyOut, BigDecimal net, BigDecimal cashIn, BigDecimal cashOut, List<Part> inParts,
                       List<Part> outParts) {
    }

    /** Profit and loss of a period (funding left out; amounts before GST). */
    public record Pnl(BigDecimal sales, BigDecimal otherIncome, BigDecimal revenue, BigDecimal purchases, BigDecimal salaries,
                      BigDecimal otherExpenses, BigDecimal discounts, BigDecimal expenses, BigDecimal profit,
                      BigDecimal gstOnSales, BigDecimal gstOnPurchases) {
    }

    /**
     * The bank account from tallyFrom to tallyTo (the part of the period whose balances are known: from the first
     * known balance to the last): the balance before, what the portal says went in and out of the bank (not cash), the
     * balance that should be after, and the bank's own figures. Null where nothing is known.
     */
    public record Tally(BigDecimal opening, BigDecimal portalIn, BigDecimal portalOut, BigDecimal expected,
                        BigDecimal actual, BigDecimal difference, BigDecimal bankIn, BigDecimal bankOut, boolean covered,
                        LocalDate tallyFrom, LocalDate tallyTo) {
    }

    public record Bucket(String key, String label, LocalDate from, LocalDate to, Flow flow, Pnl pnl, Tally tally) {
    }

    /** What a bank line was matched to in the portal. */
    public record Match(String type, Long id, String number, String label, Long docId) {
    }

    public record BankLineView(Long id, LocalDate date, String description, String reference, BigDecimal credit, BigDecimal debit,
                               BigDecimal balance, String status, List<Match> matches, BigDecimal recorded) {
    }

    /** Money in the portal (not cash) not found in the bank statement. */
    public record Movement(String type, Long id, LocalDate date, boolean in, BigDecimal amount, String label, String number,
                           Long docId, String reference) {
    }

    /** An issued invoice not fully paid: the difference to collect, or to write off as a discount. */
    public record ShortPaid(Long invoiceId, String number, LocalDate date, String brandCode, Long customerId, String customerName,
                            String customerPhone, Long projectId, String projectCode, String projectName, BigDecimal total,
                            BigDecimal paid, BigDecimal writtenOff, BigDecimal due, LocalDate lastPaidOn, long daysOpen) {
    }

    public record Report(LocalDate from, LocalDate to, String groupBy, Long brandId, String brandCode, List<Bucket> buckets,
                         Bucket total, List<BankLineView> bankLines, List<Movement> notInBank, List<ShortPaid> shortPaid,
                         List<StatementView> statements, List<BalanceView> balances, List<String> notes,
                         Map<String, Integer> counts) {
    }
}
