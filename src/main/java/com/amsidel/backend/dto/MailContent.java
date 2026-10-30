/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * One personal mail's content, the same for staff mail and for customer and supplier mail. {name} in the subject,
 * greeting or text becomes the person's name. Who it goes to is in the request that extends this.
 */
@Getter
@Setter
public class MailContent {

    @NotBlank
    @Size(max = 200)
    private String subject;

    /** After "Dear": empty = the person's name; or any text, e.g. "All" or "Team". */
    @Size(max = 100)
    private String greeting;

    /**
     * [[image 1]], [[image 2]] ... mark where the pictures go; pictures not marked come after the text.
     * **words** are bold and a line starting with "# " is a heading.
     */
    @NotBlank
    @Size(max = 20000)
    private String body;

    /** Copied on every person's mail (e.g. HR); optional. The office copy address is always added as well. */
    @Size(max = 10, message = "At most 10 CC addresses")
    private List<@NotBlank @Email(message = "Check the CC addresses") String> cc;

    /** Hidden copies on every person's mail; optional. */
    @Size(max = 10, message = "At most 10 BCC addresses")
    private List<@NotBlank @Email(message = "Check the BCC addresses") String> bcc;

    /** The sign-off after the message (**bold** allowed); empty = the sender's saved signature or the standard one. */
    @Size(max = 1000, message = "The signature can be at most 1000 characters")
    private String signature;

    /** Files uploaded before sending, attached to the mail. */
    @Size(max = 10, message = "At most 10 attachments")
    private List<@NotBlank String> attachments;

    /** Pictures inside the message, in order: [[image 1]] is the first. */
    @Size(max = 10, message = "At most 10 pictures in the message")
    private List<@Valid Picture> images;

    @Getter
    @Setter
    public static class Picture {
        @NotBlank
        private String fileId;

        /** Shown under the picture; optional. */
        @Size(max = 300)
        private String caption;

        /** LEFT, CENTER (default) or RIGHT. */
        @jakarta.validation.constraints.Pattern(regexp = "LEFT|CENTER|RIGHT", message = "Picture position: LEFT, CENTER or RIGHT")
        private String align;

        /** SMALL (200px), MEDIUM (360px) or LARGE (the full width, default). */
        @jakarta.validation.constraints.Pattern(regexp = "SMALL|MEDIUM|LARGE", message = "Picture size: SMALL, MEDIUM or LARGE")
        private String size;
    }
}
