/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AppSetting;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.MailBrandConfig;
import com.amsidel.backend.entity.MailDailyCount;
import com.amsidel.backend.repository.AppSettingRepository;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.MailBrandConfigRepository;
import com.amsidel.backend.repository.MailDailyCountRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Staff email limits per brand (Email > Staff email config); see MailLimitsService. */
@Service
public class StaffMailConfig extends MailLimitsService<MailBrandConfig, MailDailyCount> {

    static final String PICK_LIMIT = "mail.pick-limit";

    private final BrandRepository brands;
    private final AppSettingRepository settings;

    public StaffMailConfig(MailBrandConfigRepository configs, MailDailyCountRepository counts, BrandRepository brands,
                           AppSettingRepository settings, @Value("${app.leave.zone}") ZoneId zone,
                           @Value("${app.mail.always-cc:info.amsidel@gmail.com}") String defaultCc, BrandMailSenders senders) {
        super(configs, counts, brands, zone, defaultCc, senders);
        this.brands = brands;
        this.settings = settings;
    }

    public static long brandKeyOf(Employee e) {
        return e.getBrand() == null ? NO_BRAND : e.getBrand().getId();
    }

    /** Every brand for owners and group admins; else my own. */
    @Override
    protected List<Brand> brandsFor(AuthUser me) {
        if (seesNoBrand(me)) {
            return brands.findAllByOrderByNameAsc();
        }
        return brands.findById(me.brandId()).map(List::of).orElse(List.of());
    }

    @Override
    protected boolean seesNoBrand(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }

    /** The one limit set before limits went per brand, or 5. */
    @Override
    protected int defaultPickLimit() {
        return settings.findById(PICK_LIMIT).map(AppSetting::getValue).map(StaffMailConfig::parse).orElse(DEFAULT_PICK_LIMIT);
    }

    @Override
    protected String defaultFooter(String brandName) {
        return "Sent from the Amsidel Company Portal";
    }

    @Override
    protected MailBrandConfig newConfig(long brandKey) {
        MailBrandConfig c = new MailBrandConfig();
        c.setBrandKey(brandKey);
        return c;
    }

    @Override
    protected MailDailyCount newCount(long brandKey, LocalDate day) {
        MailDailyCount c = new MailDailyCount();
        c.setBrandKey(brandKey);
        c.setSentOn(day);
        return c;
    }

    @Override
    protected String configPage() {
        return "Email > Staff email config";
    }

    @Override
    protected String mailName() {
        return "staff email";
    }

    private static int parse(String value) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_PICK_LIMIT;
        }
    }
}
