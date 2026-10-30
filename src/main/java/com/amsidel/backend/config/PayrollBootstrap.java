/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import com.amsidel.backend.entity.PayComponent;
import com.amsidel.backend.entity.PayComponentKind;
import com.amsidel.backend.repository.PayComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import static com.amsidel.backend.entity.PayComponentKind.*;

/** First start: the pay components of the standard payslip. Owners and HR change them under Payroll > Pay components. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayrollBootstrap implements ApplicationRunner {

    private final PayComponentRepository components;

    @Override
    public void run(ApplicationArguments args) {
        if (components.count() > 0) {
            markTaxes();
            spellOut();
            return;
        }
        add("BASIC", "Basic", EARNING, 10);
        add("DA", "Dearness allowance", EARNING, 20);
        add("HRA", "House Rent Allowance", EARNING, 30);
        add("LTA", "Leave Travel Allowance", EARNING, 40);
        add("SPECIAL", "Special allowance", EARNING, 50);
        add("EPF", "EPF Contribution", DEDUCTION, 10);
        add("LWF", "Labour Welfare Fund", DEDUCTION, 20);
        add("TDS", "Income Tax", DEDUCTION, 30, true);
        add("PT", "Professional Tax", DEDUCTION, 40, true);
        add("FUEL", "Fuel Reimbursement", REIMBURSEMENT, 10);
        add("PHONE", "Telephone Reimbursement", REIMBURSEMENT, 20);
        log.info("Created the standard pay components");
    }

    private void add(String code, String name, PayComponentKind kind, int order) {
        add(code, name, kind, order, false);
    }

    private void add(String code, String name, PayComponentKind kind, int order, boolean tax) {
        PayComponent c = new PayComponent();
        c.setCode(code);
        c.setName(name);
        c.setKind(kind);
        c.setSortOrder(order);
        c.setTax(tax);
        components.save(c);
    }

    /** Components made with a short name: LTA is Leave Travel Allowance (a name changed by hand is kept). */
    private void spellOut() {
        int n = components.rename("LTA", "LTA", "Leave Travel Allowance");
        int lines = components.renameOnPayslips("LTA", "LTA", "Leave Travel Allowance");
        if (n + lines > 0) {
            log.info("Renamed LTA to Leave Travel Allowance ({} component, {} payslip lines)", n, lines);
        }
    }

    /** Components made before the "tax" flag existed: Income Tax and Professional Tax are taxes. */
    private void markTaxes() {
        if (components.findAll().stream().anyMatch(PayComponent::isTax)) {
            return;
        }
        components.findAll().stream().filter(c -> c.getCode().equals("TDS") || c.getCode().equals("PT")).forEach(c -> {
            c.setTax(true);
            components.save(c);
        });
    }
}
