/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.OnlineMethod;
import com.amsidel.backend.entity.OnlinePayStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Pay bill (Product portal): bills paid online by card or UPI through the brand's Razorpay account. */
public final class OnlinePayDtos {

    private OnlinePayDtos() {
    }

    /** The processing fee for a way of paying. */
    public record FeeView(OnlineMethod method, BigDecimal percent, Instant updatedAt, String updatedByName) {
    }

    /**
     * A brand I may take payments for; card / upi: whether its Razorpay account takes them (null = not known);
     * mode TEST or LIVE from its key, null when it has no keys; shared = the company's default keys. */
    public record BrandPay(Long id, String code, String name, boolean ready, String mode, boolean shared, Boolean card, Boolean upi,
                           String manualUpiId, boolean manualReady) {
    }

    /** canManual: the Manual UPI (office) checkpoint is mine. */
    public record PaySetup(List<FeeView> fees, List<BrandPay> brands, boolean canRefund, boolean canSetFees, boolean canManual) {
    }

    /** A customer with bills still to pay. */
    public record CustomerDue(Long id, String code, String name, String phone, String email, Long brandId, String brandCode,
                              int bills, BigDecimal due) {
    }

    /** An issued invoice with money still due. */
    public record OpenBill(Long id, String number, LocalDate docDate, BigDecimal total, BigDecimal paid, BigDecimal writtenOff,
                           BigDecimal due, Long customerId, String customerName, Long brandId, String brandCode) {
    }

    @Getter
    @Setter
    public static class StartBody {
        @NotNull
        private Long invoiceId;

        @NotNull
        private OnlineMethod method;

        @NotNull
        @DecimalMin(value = "1.00", message = "Pay at least ₹1")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;
    }

    /** What the browser needs to open Razorpay's payment window. amount is in paise. */
    public record Checkout(Long id, String keyId, String orderId, long amount, String currency, String brandName, String description,
                           String customerName, String customerEmail, String customerPhone, OnlineMethod method,
                           BigDecimal billAmount, BigDecimal feePercent, BigDecimal fee, BigDecimal total, String payeeUpiId) {
    }

    /** Manual UPI: what the staff saw in the brand's account once the customer paid. */
    @Getter
    @Setter
    public static class ManualBody {
        @NotBlank(message = "Give the UPI reference (UTR) of the payment")
        @Pattern(regexp = "[A-Za-z0-9]{6,35}", message = "The UPI reference is 6 to 35 letters or digits (usually the 12-digit UTR)")
        private String reference;

        @Pattern(regexp = "|[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}", message = "The payer's UPI ID looks like name@bank")
        @Size(max = 100)
        private String payerUpiId;

        @NotNull
        private LocalDate paidOn;

        @Size(max = 300)
        private String remarks;
    }

    /** What Razorpay's window hands back after paying. */
    @Getter
    @Setter
    public static class VerifyBody {
        @NotBlank
        @Size(max = 40)
        private String paymentId;

        @NotBlank
        @Size(max = 40)
        private String orderId;

        @NotBlank
        @Size(max = 200)
        private String signature;
    }

    /** The payment window reported a failure, or was closed (cancelled). */
    @Getter
    @Setter
    public static class FailBody {
        private boolean cancelled;

        @Size(max = 500)
        private String reason;

        @Size(max = 40)
        private String paymentId;
    }

    @Getter
    @Setter
    public static class RefundBody {
        @NotNull
        @DecimalMin(value = "1.00", message = "Refund at least ₹1")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;

        @NotBlank(message = "Say why it is refunded")
        @Size(max = 300)
        private String reason;
    }

    @Getter
    @Setter
    public static class FeeBody {
        @NotNull
        private OnlineMethod method;

        @NotNull
        @DecimalMin(value = "0.00", message = "The fee cannot be below 0%")
        @DecimalMax(value = "10.00", message = "The fee can be at most 10%")
        @Digits(integer = 2, fraction = 2, message = "Use at most 2 decimals")
        private BigDecimal percent;
    }

    /** Where the money came from, kept for refunds (no full card number, expiry or CVV: those never reach the portal). */
    public record SourceView(Long id, Long onlinePaymentId, String transactionId, String method, BigDecimal amount,
                             String cardNetwork, String cardLast4, String cardIssuer, String cardType, Boolean cardInternational,
                             String vpa, String bank, String payerEmail, String payerPhone, Long customerId, String customerName,
                             String billNumber, String brandCode, Instant createdAt, String label) {
    }

    /** One online payment: the request sent and what came of it. */
    public record OnlineView(Long id, Long brandId, String brandCode, Long customerId, String customerName, Long invoiceId, String billNumber,
                             OnlineMethod method, OnlinePayStatus status, BigDecimal billAmount, BigDecimal feePercent, BigDecimal fee,
                             BigDecimal total, String currency, String orderId, String paymentId, String failure, String note,
                             BigDecimal refunded, Long tradePaymentId, String requestedByName, Instant createdAt, Instant paidAt,
                             SourceView source, String payeeUpiId) {
    }
}
