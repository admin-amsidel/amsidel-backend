/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.Totals;
import com.amsidel.backend.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The money of a quotation, invoice or purchase bill: lines (quantity x rate), less a discount, plus other charges
 * = taxable; GST on that; the total rounded to the rupee (the difference is the round-off).
 */
public record TradeMoney(BigDecimal subtotal, BigDecimal discount, BigDecimal miscAmount, BigDecimal taxable,
                         BigDecimal gstRate, BigDecimal gstAmount, BigDecimal roundOff, BigDecimal total) {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static BigDecimal amount(BigDecimal quantity, BigDecimal rate) {
        return quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public static TradeMoney of(List<BigDecimal> lineAmounts, BigDecimal discount, BigDecimal misc, BigDecimal gstRate) {
        BigDecimal subtotal = lineAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal off = scale(discount);
        if (off.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("The discount is more than the lines add up to");
        }
        BigDecimal extra = scale(misc);
        BigDecimal taxable = subtotal.subtract(off).add(extra);
        BigDecimal rate = gstRate == null ? BigDecimal.ZERO : gstRate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst = taxable.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal exact = taxable.add(gst);
        BigDecimal total = exact.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
        return new TradeMoney(subtotal, off, extra, taxable, rate, gst, total.subtract(exact), total);
    }

    /** For a view: GST split into CGST + SGST (same state) or IGST (another state). */
    public static Totals totals(BigDecimal subtotal, BigDecimal discount, String miscLabel, BigDecimal misc,
                                BigDecimal taxable, BigDecimal gstRate, boolean interState, BigDecimal gst,
                                BigDecimal roundOff, BigDecimal total, BigDecimal paid) {
        return totals(subtotal, discount, miscLabel, misc, taxable, gstRate, interState, gst, roundOff, total, paid, BigDecimal.ZERO.setScale(2));
    }

    /** writtenOff: discount written off after issue; it is settled like a payment. */
    public static Totals totals(BigDecimal subtotal, BigDecimal discount, String miscLabel, BigDecimal misc,
                                BigDecimal taxable, BigDecimal gstRate, boolean interState, BigDecimal gst,
                                BigDecimal roundOff, BigDecimal total, BigDecimal paid, BigDecimal writtenOff) {
        BigDecimal half = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal due = total.subtract(paid).subtract(writtenOff);
        return new Totals(subtotal, discount, miscLabel, misc, taxable, gstRate, interState, gst,
                interState ? BigDecimal.ZERO : half, interState ? BigDecimal.ZERO : gst.subtract(half),
                interState ? gst : BigDecimal.ZERO, roundOff, total, paid, due.signum() < 0 ? BigDecimal.ZERO : due, writtenOff);
    }

    /** UNPAID, PARTLY_PAID or PAID. */
    public static String payStatus(BigDecimal total, BigDecimal paid) {
        if (paid.signum() <= 0) {
            return total.signum() == 0 ? "PAID" : "UNPAID";
        }
        return paid.compareTo(total) >= 0 ? "PAID" : "PARTLY_PAID";
    }

    static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
