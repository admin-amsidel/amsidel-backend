/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/** One brand's staff email limits (Email > Staff email config). */
@Entity
@Table(name = "mail_brand_config")
@NoArgsConstructor
public class MailBrandConfig extends MailLimits {
}
