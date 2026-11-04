/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.WorkHourRuleRequest;
import com.amsidel.backend.dto.WorkTarget;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.BrandWorkRule;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.BrandWorkRuleRepository;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Work hour rules per brand: each brand's default, and optionally the hours for a designation in it. Someone's minimum
 * comes from their designation's rule in their brand, else their brand's default, else the group default. A person's
 * own target (WorkLogService) goes before all of them. Someone with a brand changes only their brand's rules.
 */
@Service
@RequiredArgsConstructor
public class WorkRuleService {

    /** Only if the group default was ever removed from the database. */
    private static final WorkTarget FALLBACK = new WorkTarget(new BigDecimal("9"), new BigDecimal("54"), new BigDecimal("4.5"), BigDecimal.ONE, "Default");

    private final BrandWorkRuleRepository rules;
    private final BrandRepository brands;
    private final DesignationRepository designations;

    /**
     * One row of the screen: a brand's default (designationId null) or a designation in it, with its own hours (null when
     * it has no rule of its own) and what applies without them (inherited).
     */
    public record Row(Long brandId, String brandCode, String brandName, Long designationId, String designationName,
                      Integer levelNumber, String levelCode, BigDecimal dailyMinHours, BigDecimal weeklyMinHours,
                      BigDecimal halfDayBelow, BigDecimal fullDayBelow, WorkTarget inherited) {
    }

    /** Every default and designation of the brands I may change, each brand's ladder in level order. */
    @Transactional(readOnly = true)
    public List<Row> rows(AuthUser me) {
        Map<String, BrandWorkRule> all = rules.findAll().stream().collect(Collectors.toMap(r -> r.getBrandKey() + ":" + r.getDesignationKey(), r -> r));
        List<Designation> ladder = designations.listWithBrands();
        List<Row> out = new ArrayList<>();
        for (Brand b : scope(me)) {
            addBrand(out, b, ladder, all);
        }
        if (seesAll(me)) {
            addBrand(out, null, ladder, all);
        }
        return out;
    }

    /** Saves a brand's default, or its hours for a designation. */
    @Transactional
    public List<Row> save(AuthUser me, WorkHourRuleRequest body) {
        long brandKey = brandKeyFor(me, body.getBrandId());
        long designationKey = designationKeyFor(body);
        if (body.getFullDayBelow().compareTo(body.getHalfDayBelow()) > 0) {
            throw ApiException.badRequest("The Full Day Leave limit must not be above the Half Day one");
        }
        if (body.getHalfDayBelow().compareTo(body.getDailyMinHours()) > 0) {
            throw ApiException.badRequest("The Half Day Leave limit must not be above the hours a day");
        }
        BrandWorkRule rule = rules.findByBrandKeyAndDesignationKey(brandKey, designationKey).orElseGet(BrandWorkRule::new);
        rule.setBrandKey(brandKey);
        rule.setDesignationKey(designationKey);
        rule.setDailyMinHours(body.getDailyMinHours());
        rule.setWeeklyMinHours(body.getWeeklyMinHours());
        rule.setHalfDayBelow(body.getHalfDayBelow());
        rule.setFullDayBelow(body.getFullDayBelow());
        rules.save(rule);
        return rows(me);
    }

    /** Takes a rule off: what is above it applies again. The group default stays. */
    @Transactional
    public List<Row> remove(AuthUser me, Long brandId, Long designationId) {
        long brandKey = brandKeyFor(me, brandId);
        long designationKey = designationId == null ? BrandWorkRule.DEFAULT : designationId;
        if (brandKey == BrandWorkRule.NO_BRAND && designationKey == BrandWorkRule.DEFAULT) {
            throw ApiException.badRequest("The group default is required: every other rule falls back to it");
        }
        rules.findByBrandKeyAndDesignationKey(brandKey, designationKey).ifPresent(rules::delete);
        rules.flush();
        return rows(me);
    }

    /** The minimum for this person from the rules. Call inside a transaction: reads the brand and designation. */
    WorkTarget target(Employee person) {
        Brand brand = person.getBrand();
        long brandKey = brand == null ? BrandWorkRule.NO_BRAND : brand.getId();
        Designation d = person.getDesignation();
        if (d != null) {
            BrandWorkRule own = rules.findByBrandKeyAndDesignationKey(brandKey, d.getId()).orElse(null);
            if (own != null) {
                return target(own, d.getName() + " rule");
            }
        }
        return inherited(brandKey, brand == null ? null : brand.getCode());
    }

    /** The brand's default, else the group default. */
    private WorkTarget inherited(long brandKey, String brandCode) {
        BrandWorkRule brandDefault = brandKey == BrandWorkRule.NO_BRAND ? null
                : rules.findByBrandKeyAndDesignationKey(brandKey, BrandWorkRule.DEFAULT).orElse(null);
        return brandDefault != null ? target(brandDefault, brandCode + " default") : groupDefault();
    }

    private WorkTarget groupDefault() {
        return rules.findByBrandKeyAndDesignationKey(BrandWorkRule.NO_BRAND, BrandWorkRule.DEFAULT)
                .map(r -> target(r, "Group default")).orElse(FALLBACK);
    }

    private static WorkTarget target(BrandWorkRule r, String source) {
        return new WorkTarget(r.getDailyMinHours(), r.getWeeklyMinHours(), r.getHalfDayBelow(), r.getFullDayBelow(), source);
    }

    private void addBrand(List<Row> out, Brand b, List<Designation> ladder, Map<String, BrandWorkRule> all) {
        long brandKey = b == null ? BrandWorkRule.NO_BRAND : b.getId();
        Long brandId = b == null ? null : b.getId();
        String code = b == null ? null : b.getCode();
        String name = b == null ? "Group (no brand)" : b.getName();
        WorkTarget above = groupDefault();
        BrandWorkRule own = all.get(brandKey + ":" + BrandWorkRule.DEFAULT);
        out.add(row(brandId, code, name, null, own, above));
        WorkTarget brandLevel = own != null ? target(own, (code == null ? "Group" : code) + " default") : above;
        for (Designation d : ladder) {
            boolean applies = d.getBrand() == null || (b != null && d.getBrand().getId().equals(b.getId()));
            if (applies) {
                out.add(row(brandId, code, name, d, all.get(brandKey + ":" + d.getId()), brandLevel));
            }
        }
    }

    private static Row row(Long brandId, String code, String name, Designation d, BrandWorkRule r, WorkTarget inherited) {
        return new Row(brandId, code, name, d == null ? null : d.getId(), d == null ? null : d.getName(),
                d == null ? null : d.getLevelNumber(), d == null ? null : d.getLevelCode(),
                r == null ? null : r.getDailyMinHours(), r == null ? null : r.getWeeklyMinHours(),
                r == null ? null : r.getHalfDayBelow(), r == null ? null : r.getFullDayBelow(), inherited);
    }

    private long designationKeyFor(WorkHourRuleRequest body) {
        if (body.getDesignationId() == null) {
            return BrandWorkRule.DEFAULT;
        }
        Designation d = designations.findById(body.getDesignationId())
                .orElseThrow(() -> ApiException.badRequest("Designation not found: " + body.getDesignationId()));
        if (d.getBrand() != null && !Objects.equals(d.getBrand().getId(), body.getBrandId())) {
            throw ApiException.badRequest(d.getName() + " is a designation of another brand");
        }
        return d.getId();
    }

    private long brandKeyFor(AuthUser me, Long brandId) {
        if (!seesAll(me) && (brandId == null || !brandId.equals(me.brandId()))) {
            throw ApiException.forbidden("You can only change your own brand's work hour rules");
        }
        if (brandId == null) {
            return BrandWorkRule.NO_BRAND;
        }
        if (!brands.existsById(brandId)) {
            throw ApiException.badRequest("Brand not found: " + brandId);
        }
        return brandId;
    }

    private List<Brand> scope(AuthUser me) {
        if (seesAll(me)) {
            return brands.findAllByOrderByNameAsc();
        }
        return brands.findById(me.brandId()).map(List::of).orElse(List.of());
    }

    private static boolean seesAll(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }
}
