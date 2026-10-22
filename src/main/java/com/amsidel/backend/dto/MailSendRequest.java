package com.amsidel.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * One personal mail to one employee. {name} in the subject, greeting or text becomes the employee's name.
 * The portal sends one of these per selected person, so every mail goes out on its own.
 */
@Data
public class MailSendRequest {

    @NotNull
    private Long employeeId;

    @NotBlank
    @Size(max = 200)
    private String subject;

    /** After "Dear": empty = the employee's name; or any text, e.g. "All" or "Team". */
    @Size(max = 100)
    private String greeting;

    /** [[image 1]], [[image 2]] ... mark where the pictures go; pictures not marked come after the text. */
    @NotBlank
    @Size(max = 20000)
    private String body;

    /** Copied on every person's mail (e.g. HR); optional. */
    @Size(max = 10, message = "At most 10 CC addresses")
    private List<@NotBlank @Email(message = "Check the CC addresses") String> cc;

    /** Hidden copies on every person's mail; optional. */
    @Size(max = 10, message = "At most 10 BCC addresses")
    private List<@NotBlank @Email(message = "Check the BCC addresses") String> bcc;

    /** Files from POST /api/mail/files, attached to the mail. */
    @Size(max = 10, message = "At most 10 attachments")
    private List<@NotBlank String> attachments;

    /** Pictures inside the message, in order: [[image 1]] is the first. */
    @Size(max = 10, message = "At most 10 pictures in the message")
    private List<@Valid Picture> images;

    @Data
    public static class Picture {
        @NotBlank
        private String fileId;

        /** Shown under the picture; optional. */
        @Size(max = 300)
        private String caption;
    }
}
