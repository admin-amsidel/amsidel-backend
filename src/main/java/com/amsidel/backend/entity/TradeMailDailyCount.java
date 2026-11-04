/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.NoArgsConstructor;

/** Customer &amp; supplier emails per brand and day. */
@Entity
@Table(name = "trade_mail_daily_counts", uniqueConstraints = @UniqueConstraint(name = "uk_trade_mail_daily_count", columnNames = {"brand_key", "sent_on"}))
@NoArgsConstructor
public class TradeMailDailyCount extends MailCount {
}
