/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * An enquiry from the public website (quote form, site-visit form, AMI chat), mailed to the enquiry inbox.
 * The website sends the rows it shows; the server lays them out.
 */
@Data
public class EnquiryRequest {

    public enum Kind { QUOTE, SITE_VISIT, CHAT }

    @NotNull
    private Kind kind;

    /** What it is about, e.g. "Casement windows" or "Mon, 26 Oct 2026, 10:00 AM – Kothamangalam". */
    @Size(max = 150)
    private String headline;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 40)
    private String phone;

    @Email(message = "Check the email address")
    @Size(max = 150)
    private String email;

    /** Label and value rows, in order (e.g. Location, Interested in, Visit date). */
    @Size(max = 15)
    private List<@Valid Row> details;

    @Size(max = 4000)
    private String message;

    /** AMI chat: the conversation. */
    @Size(max = 200)
    private List<@Valid ChatLine> transcript;

    /** The page it was sent from. */
    @Size(max = 300)
    private String page;

    /** Hidden field: people leave it empty, bots fill it in. */
    @Size(max = 200)
    private String website;

    @Data
    public static class Row {
        @NotBlank
        @Size(max = 60)
        private String label;

        @Size(max = 1000)
        private String value;
    }

    @Data
    public static class ChatLine {
        /** true: the visitor; false: AMI. */
        private boolean visitor;

        @Size(max = 2000)
        private String text;
    }
}
