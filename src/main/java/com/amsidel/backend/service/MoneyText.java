/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.CurrencyCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money as printed on a brand's documents, in its currency: "₹2,14,945.00" / "$214,945.00", and in words. */
public final class MoneyText {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private MoneyText() {
    }

    public static String format(BigDecimal v, CurrencyCode c) {
        if (c == null || c == CurrencyCode.INR) {
            return PayslipPdf.rupees(v);
        }
        BigDecimal a = (v == null ? BigDecimal.ZERO : v).setScale(c.decimals(), RoundingMode.HALF_UP);
        String digits = a.abs().toPlainString();
        int dot = digits.indexOf('.');
        String whole = dot < 0 ? digits : digits.substring(0, dot);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                s.append(',');
            }
            s.append(whole.charAt(i));
        }
        // a symbol in letters (AED, CHF) stands apart from the number
        String symbol = c.symbol().chars().allMatch(Character::isLetter) ? c.symbol() + " " : c.symbol();
        return (a.signum() < 0 ? "-" : "") + symbol + s + (dot < 0 ? "" : digits.substring(dot));
    }

    /** "US Dollar One Hundred Twenty-Three Thousand Four Hundred Fifty-Six and Seventy Cents Only"; rupees the Indian way. */
    public static String words(BigDecimal amount, CurrencyCode c) {
        if (c == null || c == CurrencyCode.INR) {
            return RupeesInWords.of(amount);
        }
        BigDecimal a = amount.setScale(c.decimals(), RoundingMode.HALF_UP).abs();
        long whole = a.longValue();
        int cents = c.decimals() == 0 ? 0 : a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder out = new StringBuilder(c.unit()).append(' ').append(whole == 0 ? "Zero" : international(whole));
        if (cents > 0 && c.subunit() != null) {
            out.append(" and ").append(below100(cents)).append(' ').append(c.subunit());
        }
        return out.append(" Only").toString();
    }

    /** Thousands, millions, billions. */
    static String international(long n) {
        String[] scale = {"", " Thousand", " Million", " Billion", " Trillion"};
        StringBuilder s = new StringBuilder();
        int i = 0;
        while (n > 0) {
            int chunk = (int) (n % 1000);
            if (chunk > 0) {
                s.insert(0, below1000(chunk) + scale[i] + " ");
            }
            n /= 1000;
            i++;
        }
        return s.toString().strip();
    }

    private static String below1000(int n) {
        int h = n / 100;
        int rest = n % 100;
        String out = h > 0 ? ONES[h] + " Hundred" : "";
        if (rest > 0) {
            out += (out.isEmpty() ? "" : " ") + below100(rest);
        }
        return out;
    }

    private static String below100(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
    }
}
