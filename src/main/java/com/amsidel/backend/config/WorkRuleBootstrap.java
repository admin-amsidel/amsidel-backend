/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.BrandWorkRule;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.WorkHourRule;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.BrandWorkRuleRepository;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.repository.WorkHourRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The first time work hour rules are per brand: the rules until then (one default, and one per level) become each brand's
 * default and its designations' rules, and the group's, so that nobody's minimum changes. The old rows are left as they
 * were and no longer used.
 */
@Slf4j
@Component
@Order(2) // after DataBootstrap, which makes the first rule, brands and designations
@RequiredArgsConstructor
public class WorkRuleBootstrap implements ApplicationRunner {

    private final BrandWorkRuleRepository rules;
    private final WorkHourRuleRepository oldRules;
    private final BrandRepository brands;
    private final DesignationRepository designations;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            if (rules.count() > 0) {
                return;
            }
            List<Designation> ladder = designations.listWithBrands();
            List<Brand> everyone = new ArrayList<>(brands.findAll());
            everyone.add(null);
            int made = 0;
            for (WorkHourRule old : oldRules.findAll()) {
                for (Brand b : everyone) {
                    if (old.getLevelNumber() == WorkHourRule.DEFAULT_LEVEL) {
                        rules.save(copy(old, b, BrandWorkRule.DEFAULT));
                        made++;
                        continue;
                    }
                    for (Designation d : ladder) {
                        boolean applies = d.getBrand() == null || (b != null && d.getBrand().getId().equals(b.getId()));
                        if (applies && d.getLevelNumber() == old.getLevelNumber()) {
                            rules.save(copy(old, b, d.getId()));
                            made++;
                        }
                    }
                }
            }
            log.info("Work hour rules are per brand now: {} rule(s) made from the old ones", made);
        });
    }

    private static BrandWorkRule copy(WorkHourRule old, Brand brand, long designationKey) {
        BrandWorkRule r = new BrandWorkRule();
        r.setBrandKey(brand == null ? BrandWorkRule.NO_BRAND : brand.getId());
        r.setDesignationKey(designationKey);
        r.setDailyMinHours(old.getDailyMinHours());
        r.setWeeklyMinHours(old.getWeeklyMinHours());
        r.setHalfDayBelow(old.getHalfDayBelow());
        r.setFullDayBelow(old.getFullDayBelow());
        return r;
    }
}
