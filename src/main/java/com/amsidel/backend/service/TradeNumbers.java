package com.amsidel.backend.service;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.DocSequence;
import com.amsidel.backend.repository.DocSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Bill and customer numbers, one after another without repeats: the brand code, what it is (SLD invoice, QTN
 * quotation, PUR purchase), the month and year of the bill, and a running number for that month, e.g.
 * DWSLD-102026-001. Customers: DWC-0001.
 */
@Component
@RequiredArgsConstructor
public class TradeNumbers {

    public static final String SALE = "SLD";
    public static final String QUOTATION = "QTN";
    public static final String PURCHASE = "PUR";

    private final DocSequenceRepository sequences;

    /** Inside the caller's transaction: the counter stays locked until the bill is saved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Brand brand, String kind, LocalDate date) {
        String prefix = brand.getCode().toUpperCase(Locale.ROOT) + kind + "-"
                + String.format("%02d%04d", date.getMonthValue(), date.getYear());
        return prefix + "-" + String.format("%03d", take(prefix));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String customer(Brand brand) {
        String prefix = brand.getCode().toUpperCase(Locale.ROOT) + "C";
        return prefix + "-" + String.format("%04d", take(prefix));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String supplier(Brand brand) {
        String prefix = brand.getCode().toUpperCase(Locale.ROOT) + "S";
        return prefix + "-" + String.format("%03d", take(prefix));
    }

    private int take(String prefix) {
        DocSequence s = sequences.lock(prefix).orElseGet(() -> {
            DocSequence fresh = new DocSequence();
            fresh.setPrefix(prefix);
            return fresh;
        });
        s.setLastNumber(s.getLastNumber() + 1);
        sequences.saveAndFlush(s);
        return s.getLastNumber();
    }
}
