/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.OfferLetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class OfferLetterDtos {

    private OfferLetterDtos() {
    }

    /** A letter as written: only the brand and the candidate are needed for a draft; issuing checks the rest. */
    @Data
    public static class Body {
        @NotNull(message = "Choose the brand")
        private Long brandId;

        private LocalDate issueDate;

        @Size(max = 200, message = "Company name: at most 200 characters")
        private String companyName;

        @Size(max = 500, message = "Company address: at most 500 characters")
        private String companyAddress;

        @Size(max = 4000, message = "About the company: at most 4000 characters")
        private String companyIntro;

        @NotBlank(message = "Give the candidate's name")
        @Size(max = 150, message = "Candidate's name: at most 150 characters")
        private String candidateName;

        @Size(max = 500, message = "Candidate's address: at most 500 characters")
        private String candidateAddress;

        @Size(max = 150, message = "Designation: at most 150 characters")
        private String designation;

        private LocalDate joiningDate;

        private LocalDate startDate;

        @DecimalMin(value = "0", inclusive = false, message = "Working hours in a week: more than 0")
        @DecimalMax(value = "168", message = "Working hours in a week: at most 168")
        private BigDecimal weeklyHours;

        @DecimalMin(value = "0", inclusive = false, message = "Annual package: more than 0")
        private BigDecimal annualPackage;

        @DecimalMin(value = "0", inclusive = false, message = "Monthly salary: more than 0")
        private BigDecimal monthlySalary;

        @Valid
        private List<LineBody> lines = new ArrayList<>();

        @Size(max = 20, message = "Contact number: at most 20 characters")
        private String contactNumber;

        @Email(message = "Contact email does not look right")
        @Size(max = 150, message = "Contact email: at most 150 characters")
        private String contactEmail;

        @Size(max = 8000, message = "Clauses and rules: at most 8000 characters")
        private String clauses;

        @Size(max = 150, message = "Issued by: at most 150 characters")
        private String issuedByName;

        @Size(max = 150, message = "Issued by designation: at most 150 characters")
        private String issuedByDesignation;
    }

    @Data
    public static class LineBody {
        @NotBlank(message = "Name each part of the package split")
        @Size(max = 100, message = "Package split: at most 100 characters a name")
        private String name;

        @NotNull(message = "Give an amount for each part of the package split")
        @DecimalMin(value = "0", inclusive = false, message = "Package split: amounts more than 0")
        private BigDecimal annualAmount;
    }

    public record LineView(String name, BigDecimal annualAmount) {
    }

    /** One letter in the list. */
    public record Row(Long id, OfferLetter.Status status, String letterNumber, Long brandId, String brandCode, String brandName,
                      String candidateName, String designation, LocalDate issueDate, LocalDate joiningDate, BigDecimal annualPackage,
                      String currencySymbol, String issuedByName, String changedByName, Instant changedAt) {
        /** Call inside a transaction: reads the brand. */
        public static Row of(OfferLetter o) {
            return new Row(o.getId(), o.getStatus(), o.getLetterNumber(), o.getBrand().getId(), o.getBrand().getCode(), o.getBrand().getName(),
                    o.getCandidateName(), o.getDesignation(), o.getIssueDate(), o.getJoiningDate(), o.getAnnualPackage(),
                    o.getBrand().getCurrency().symbol(), o.getIssuedByName(), o.getChangedByName(), o.getChangedAt());
        }
    }

    /** The list, and whether I may change and delete issued letters. */
    public record Page(List<Row> rows, boolean canChangeIssued, boolean canDeleteIssued) {
    }

    /** A letter in full, for the form. */
    public record View(Long id, OfferLetter.Status status, String letterNumber, Long brandId, LocalDate issueDate, String companyName,
                       String companyAddress, String companyIntro, String candidateName, String candidateAddress, String designation,
                       LocalDate joiningDate, LocalDate startDate, BigDecimal weeklyHours, BigDecimal annualPackage, BigDecimal monthlySalary,
                       List<LineView> lines, String contactNumber, String contactEmail, String clauses, String issuedByName,
                       String issuedByDesignation, String currencySymbol) {
        /** Call inside a transaction: reads the brand and the lines. */
        public static View of(OfferLetter o) {
            return new View(o.getId(), o.getStatus(), o.getLetterNumber(), o.getBrand().getId(), o.getIssueDate(), o.getCompanyName(),
                    o.getCompanyAddress(), o.getCompanyIntro(), o.getCandidateName(), o.getCandidateAddress(), o.getDesignation(),
                    o.getJoiningDate(), o.getStartDate(), o.getWeeklyHours(), o.getAnnualPackage(), o.getMonthlySalary(),
                    o.getLines().stream().map(l -> new LineView(l.getName(), l.getAnnualAmount())).toList(), o.getContactNumber(),
                    o.getContactEmail(), o.getClauses(), o.getIssuedByName(), o.getIssuedByDesignation(), o.getBrand().getCurrency().symbol());
        }
    }

    /** What a new letter of a brand starts with. */
    public record Defaults(String companyName, String companyAddress, String companyIntro, String contactNumber, String contactEmail,
                           BigDecimal weeklyHours, String clauses, String issuedByName, String issuedByDesignation, String currencySymbol) {
    }

    /** A letter's PDF and its file name. */
    public record PdfFile(String fileName, byte[] content) {
    }
}
