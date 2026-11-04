/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One brand's own Brevo account. */
@Data
public class MailAccountRequest {

    /** Empty = the group-level people, who have no brand. */
    private Long brandId;

    @NotBlank(message = "Give the Brevo SMTP login")
    @Size(max = 190, message = "The SMTP login is too long")
    private String smtpLogin;

    /** Blank keeps the saved key. */
    @Size(max = 300, message = "The SMTP key is too long")
    private String smtpKey;

    @NotBlank(message = "Give the address the mail comes from")
    @Email(message = "Give a valid address for the mail to come from")
    @Size(max = 254, message = "The From address is too long")
    private String fromEmail;

    @Size(max = 120, message = "The From name is too long")
    private String fromName;
}
