/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.LeaveAllocation;
import com.amsidel.backend.entity.LeavePlan;
import com.amsidel.backend.entity.LeavePlanLine;
import com.amsidel.backend.entity.LeaveType;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.LeavePlanRepository;
import com.amsidel.backend.repository.LeaveTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The first time leave plans exist: this calendar year's plan for every brand and for the group-level people, with the
 * leave types' current allowances, so that everyone's balance stays what it was. After that, plans are made under
 * Leave settings (a brand added later starts with none, and owners and admins are told).
 */
@Slf4j
@Component
@Order(2) // after DataBootstrap, which makes the leave types and the first brands
public class LeavePlanBootstrap implements ApplicationRunner {

    private final LeavePlanRepository plans;
    private final BrandRepository brands;
    private final LeaveTypeRepository types;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    public LeavePlanBootstrap(LeavePlanRepository plans, BrandRepository brands, LeaveTypeRepository types,
                              TransactionTemplate transactions, @Value("${app.leave.zone}") ZoneId zone) {
        this.plans = plans;
        this.brands = brands;
        this.types = types;
        this.transactions = transactions;
        this.zone = zone;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            if (plans.count() > 0) {
                return;
            }
            int year = LocalDate.now(zone).getYear();
            List<LeaveType> all = types.findAllByOrderBySortOrderAscNameAsc().stream().filter(LeaveType::isActive).toList();
            List<Brand> everyone = new ArrayList<>(brands.findAll());
            everyone.add(null);
            for (Brand b : everyone) {
                plans.save(plan(b, year, all));
            }
            log.info("Created this year's leave plan for {} brand(s) and the group-level people", everyone.size() - 1);
        });
    }

    private static LeavePlan plan(Brand brand, int year, List<LeaveType> all) {
        LeavePlan p = new LeavePlan();
        p.setBrand(brand);
        p.setValidFrom(LocalDate.of(year, 1, 1));
        p.setValidUntil(LocalDate.of(year, 12, 31));
        for (LeaveType t : all) {
            LeavePlanLine line = new LeavePlanLine();
            line.setPlan(p);
            line.setLeaveType(t);
            line.setDays(t.getAllocation() == LeaveAllocation.ANNUAL ? (t.getAnnualQuota() == null ? 0 : t.getAnnualQuota()) : null);
            p.getLines().add(line);
        }
        return p;
    }
}
