/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.PayComponentKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the payroll screens send. */
public final class PayrollRequests {

    private PayrollRequests() {
    }

    @Data
    public static class Component {
        @NotBlank
        @Size(max = 20)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "Letters, digits and _ only, e.g. HRA")
        private String code;

        @NotBlank
        @Size(max = 60)
        private String name;

        @NotNull
        private PayComponentKind kind;

        @Min(0)
        @Max(999)
        private int sortOrder;

        private boolean active = true;

        /** Counted as tax paid (Income Tax, Professional Tax). */
        private boolean tax;
    }

    /** A salary for one financial year: the monthly amount of each component (missing = 0). */
    @Data
    public static class Salary {
        @NotNull
        private LocalDate effectiveFrom;

        @Size(max = 500)
        private String notes;

        /** Working days in a month (per-day pay = monthly earnings / working days); empty = 26. */
        @Min(value = 1, message = "Working days: 1 to 31")
        @Max(value = 31, message = "Working days: 1 to 31")
        private Integer workingDays;

        @NotNull
        @Size(max = 50)
        private List<@Valid Line> lines;

        /** The person's PAN and salary account for payslips; optional, kept on the employee. */
        @Pattern(regexp = "|[A-Za-z]{5}[0-9]{4}[A-Za-z]", message = "PAN is 5 letters, 4 digits and a letter, e.g. ABCDE1234F")
        private String pan;

        @Pattern(regexp = "|[0-9]{6,20}", message = "Bank account number: 6 to 20 digits")
        private String bankAccount;

        @Pattern(regexp = "|[A-Za-z]{4}0[A-Za-z0-9]{6}", message = "IFSC is 11 characters: 4 letters, 0, then 6 letters or digits, e.g. HDFC0001234")
        private String ifsc;

        @Pattern(regexp = "|[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}", message = "UPI ID looks like name@bank, e.g. anu@okhdfcbank")
        private String upi;

        /** NEW / OLD: the income tax is worked out; MANUAL: typed in (needs the Manual income tax access). Empty = keep (new salary: NEW). */
        private com.amsidel.backend.entity.TaxRegime taxRegime;

        /** Old regime: deductions and exemptions for the year (80C, 80D, HRA, home-loan interest...). */
        @DecimalMin(value = "0", message = "Deductions cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "At most 2 decimals")
        private BigDecimal taxDeclared;
    }

    /** The salary being typed, for the tax under both regimes before it is saved. */
    @Data
    public static class TaxPreview {
        @NotNull
        private LocalDate effectiveFrom;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal monthlyGross;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal monthlyProfessionalTax;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2)
        private BigDecimal declared;
    }

    @Data
    public static class Line {
        @NotNull
        private Long componentId;

        @NotNull
        @DecimalMin(value = "0", message = "Amounts cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "At most 2 decimals")
        private BigDecimal monthly;
    }
}
