/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * The time zone someone works in: their brand's, or the company's default (app.leave.zone, India) for a brand with none
 * set and for people with no brand, such as the owners.
 */
@Component
public class TimeZones {

    private final BrandRepository brands;
    private final ZoneId defaultZone;

    public TimeZones(BrandRepository brands, @Value("${app.leave.zone}") ZoneId defaultZone) {
        this.brands = brands;
        this.defaultZone = defaultZone;
    }

    public ZoneId of(AuthUser me) {
        return me.brandId() == null ? defaultZone : named(brands.timeZoneOf(me.brandId()));
    }

    /** A brand's zone, from what is already loaded; the default with no brand or none set. */
    public ZoneId of(Brand brand) {
        return brand == null ? defaultZone : named(brand.getTimeZone());
    }

    private ZoneId named(String id) {
        if (!StringUtils.hasText(id)) {
            return defaultZone;
        }
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return defaultZone;
        }
    }

    /** A place's name such as Asia/Kolkata; the browser has to understand it too, so no offsets such as +05:30. */
    public static String checked(String id) {
        String name = id.strip();
        if (!ZoneId.getAvailableZoneIds().contains(name)) {
            throw ApiException.badRequest("Time zone not recognised: " + name);
        }
        return name;
    }
}
