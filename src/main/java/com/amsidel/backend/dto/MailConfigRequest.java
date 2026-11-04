/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One brand's email limits and office copy. */
@Data
public class MailConfigRequest {

    /** Empty = the group-level people, who have no brand. */
    private Long brandId;

    @NotNull(message = "Give the limit for one send")
    @Min(value = 1, message = "The limit for one send must be at least 1")
    @Max(value = 500, message = "The limit for one send can be at most 500")
    private Integer pickLimit;

    /** Empty = no daily limit; 0 stops this mail for the brand. */
    @Min(value = 0, message = "The daily limit cannot be negative")
    @Max(value = 100000, message = "The daily limit can be at most 100000")
    private Integer dailyLimit;

    /** Copied (CC) on every mail to this brand's people; empty = the default office address. */
    @Email(message = "Give a valid email address for the office copy")
    @Size(max = 254, message = "The office copy address is too long")
    private String alwaysCc;

    /** The line at the top of the mail; empty = the default. */
    @Size(max = 60, message = "The mail header can be at most 60 characters")
    private String mailHeader;

    /** The line at the bottom of the mail; empty = the default. */
    @Size(max = 200, message = "The mail footer can be at most 200 characters")
    private String mailFooter;
}
