/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One brand's own Brevo account, for all its mail; no row = the default account. */
@Entity
@Table(name = "mail_brand_account")
@Getter
@Setter
@NoArgsConstructor
public class MailBrandAccount {

    /** The brand's id; 0 for the group-level people, who have no brand. */
    @Id
    @Column(name = "brand_key")
    private Long brandKey;

    /** The login under Brevo > SMTP &amp; API, e.g. bf6436001@smtp-brevo.com. */
    @Column(name = "smtp_login", nullable = false, length = 190)
    private String smtpLogin;

    /** The SMTP key: saved, never sent back. */
    @Column(name = "smtp_key", nullable = false, length = 300)
    private String smtpKey;

    /** The address the mail comes from: a sender (or on a domain) verified in Brevo. */
    @Column(name = "from_email", nullable = false, length = 254)
    private String fromEmail;

    /** The name it comes from; null = the default (app.mail.from-name). */
    @Column(name = "from_name", length = 120)
    private String fromName;
}
