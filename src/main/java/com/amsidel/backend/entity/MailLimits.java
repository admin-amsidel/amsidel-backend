/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One brand's limits for one kind of mail; no row = the defaults. */
@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor
public abstract class MailLimits {

    /** The brand's id; 0 for the group-level people, who have no brand. */
    @Id
    @Column(name = "brand_key")
    private Long brandKey;

    /** People of this brand that Choose may reach in one send. */
    @Column(name = "pick_limit", nullable = false)
    private int pickLimit;

    /** Emails to this brand's people in a day; null = no limit. */
    @Column(name = "daily_limit")
    private Integer dailyLimit;

    /** Copied (CC) on every mail to this brand's people; null = the default office address (app.mail.always-cc). */
    @Column(name = "always_cc", length = 254)
    private String alwaysCc;

    /** The line at the top of the mail; null = the default. */
    @Column(name = "mail_header", length = 60)
    private String mailHeader;

    /** The line at the bottom of the mail; null = the default. */
    @Column(name = "mail_footer", length = 200)
    private String mailFooter;
}
