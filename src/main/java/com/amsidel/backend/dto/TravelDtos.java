/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Travel allowance: rates per km, claims typed in or tracked with GPS, their approval and settlement. */
public final class TravelDtos {

    private TravelDtos() {
    }

    // ---- what is sent ----

    public record RateBody(@DecimalMin(value = "0", message = "The rate cannot be below 0")
                           @Digits(integer = 6, fraction = 2, message = "Use at most 6 digits and 2 decimals") BigDecimal ratePerKm) {
    }

    /** customerId: one from the project portal's customers (its name is used); empty = customerName as typed. */
    public record ManualBody(
            Long customerId,
            @NotBlank(message = "Give the customer's name") @Size(max = 150) String customerName,
            @NotNull(message = "Give the meeting date") LocalDate meetingDate,
            @NotBlank(message = "Give where you started") @Size(max = 200) String fromLocation,
            @NotBlank(message = "Give where you went") @Size(max = 200) String toLocation,
            @NotBlank(message = "Give the meeting point") @Size(max = 200) String meetingPoint,
            @NotNull(message = "Give the total km covered") @DecimalMin(value = "0.1", message = "The distance must be more than 0")
            @DecimalMax(value = "5000", message = "The distance can be at most 5000 km") @Digits(integer = 4, fraction = 2) BigDecimal distanceKm,
            @DecimalMin(value = "0", message = "The food expense cannot be below 0") @Digits(integer = 9, fraction = 2) BigDecimal foodExpense,
            @DecimalMin(value = "0", message = "Other expenses cannot be below 0") @Digits(integer = 9, fraction = 2) BigDecimal otherExpense,
            @Size(max = 500) String notes) {
    }

    public record Fix(@NotNull(message = "The GPS location is needed") @DecimalMin("-90") @DecimalMax("90") Double latitude,
                      @NotNull(message = "The GPS location is needed") @DecimalMin("-180") @DecimalMax("180") Double longitude,
                      Double accuracy) {
    }

    public record StartBody(
            Long customerId,
            @NotBlank(message = "Give the customer's name") @Size(max = 150) String customerName,
            LocalDate meetingDate,
            @NotBlank(message = "Give where you are starting from") @Size(max = 200) String fromLocation,
            @Size(max = 200) String meetingPoint,
            @Size(max = 500) String notes,
            @NotNull(message = "The GPS location is needed") @Valid Fix fix) {
    }

    public record StopBody(@NotBlank(message = "Say what this place is, e.g. the customer's office") @Size(max = 200) String label,
                           @NotNull(message = "The GPS location is needed") @Valid Fix fix) {
    }

    public record CompleteBody(@Size(max = 200) String label, @NotNull(message = "The GPS location is needed") @Valid Fix fix) {
    }

    public record ConfirmBody(
            @NotNull(message = "Give the distance") @DecimalMin(value = "0.1", message = "The distance must be more than 0")
            @DecimalMax(value = "5000", message = "The distance can be at most 5000 km") @Digits(integer = 4, fraction = 2) BigDecimal distanceKm,
            @Size(max = 200) String toLocation,
            @Size(max = 200) String meetingPoint,
            @DecimalMin(value = "0", message = "The food expense cannot be below 0") @Digits(integer = 9, fraction = 2) BigDecimal foodExpense,
            @DecimalMin(value = "0", message = "Other expenses cannot be below 0") @Digits(integer = 9, fraction = 2) BigDecimal otherExpense,
            @Size(max = 500) String notes) {
    }

    /** approvedAmount: empty = the claimed amount. */
    public record DecideBody(boolean approve,
                             @DecimalMin(value = "0", message = "The amount cannot be below 0") @Digits(integer = 12, fraction = 2) BigDecimal approvedAmount,
                             @Size(max = 500) String remarks) {
    }

    public record SettleBody(@NotBlank(message = "Give the transaction ID") @Size(max = 60) String transactionId,
                             @NotNull(message = "Give the payment date") LocalDate paymentDate,
                             @NotNull(message = "Give the amount paid") @DecimalMin(value = "0.01", message = "The amount must be more than 0")
                             @Digits(integer = 12, fraction = 2) BigDecimal paymentAmount,
                             @Size(max = 500) String remarks) {
    }

    // ---- what comes back ----

    /** originalLatitude/Longitude: the GPS location when it was changed by hand (else null). */
    public record StopView(int sequence, String kind, String label, double latitude, double longitude, Double accuracy, Instant at,
                           Double originalLatitude, Double originalLongitude, String editedByName, Instant editedAt) {
    }

    /** A stop's new location; label empty = keep. */
    public record StopEdit(@NotNull(message = "Give the latitude") @DecimalMin("-90") @DecimalMax("90") Double latitude,
                           @NotNull(message = "Give the longitude") @DecimalMin("-180") @DecimalMax("180") Double longitude,
                           @Size(max = 200) String label) {
    }

    public record ClaimView(Long id, String code, Long employeeId, String employeeName, String empNumber, String brandCode, String currencySymbol,
                            String method, String status, Long customerId, String customerCode, String customerName, LocalDate meetingDate, String fromLocation, String toLocation,
                            String meetingPoint, String notes, BigDecimal distanceKm, BigDecimal shortestKm, BigDecimal longestKm,
                            String distanceSource, boolean distanceChanged, BigDecimal ratePerKm, BigDecimal travelAmount, BigDecimal foodExpense,
                            BigDecimal otherExpense, BigDecimal claimedAmount, Instant submittedAt, BigDecimal approvedAmount, String decisionRemarks,
                            String decidedByName, Instant decidedAt, Instant waitingSince, String transactionId, LocalDate paymentDate,
                            BigDecimal paymentAmount, String settlementRemarks, String settledByName, Instant settledAt, List<StopView> stops,
                            boolean canDecide, boolean canSettle, boolean canDelete, boolean canEditStops) {
    }

    /** pending: waiting for approval; allocated: approved (waiting for settlement and settled included); settled: paid. */
    public record Totals(int claims, BigDecimal pending, BigDecimal allocated, BigDecimal waiting, BigDecimal settled) {
    }

    /** One month: allocated by meeting date, settled by payment date. */
    public record Month(String month, String label, BigDecimal allocated, BigDecimal settled) {
    }

    public record MyTravel(BigDecimal ratePerKm, String currencySymbol, boolean canManual, boolean canGps, boolean canChangeDistance,
                           Totals totals, List<Month> months, List<ClaimView> claims, ClaimView openTrip) {
    }

    public record TeamTravel(Totals totals, List<Month> months, List<ClaimView> claims, List<Person> people, boolean canSettle) {
    }

    public record Person(Long id, String name, String empNumber) {
    }

    /** A customer of the project portal to pick on a claim. */
    public record CustomerOption(Long id, String code, String name, String phone, String location) {
    }

    public record RateRow(Long employeeId, String name, String empNumber, String brandCode, String designation, String currencySymbol,
                          BigDecimal ratePerKm, boolean editable) {
    }
}
