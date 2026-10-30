/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount in words the Indian way (thousand, lakh, crore), as on a payslip:
 * 214945 -> "Indian Rupee Two Lakh Fourteen Thousand Nine Hundred Forty-Five Only".
 */
public final class RupeesInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private RupeesInWords() {
    }

    public static String of(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP).abs();
        long rupees = a.longValue();
        int paise = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder out = new StringBuilder("Indian Rupee ");
        out.append(rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) {
            out.append(" and ").append(below100(paise)).append(" Paise");
        }
        return out.append(" Only").toString();
    }

    /** 1 to 99,99,99,99,999. */
    static String words(long n) {
        StringBuilder s = new StringBuilder();
        long crore = n / 1_00_00_000;
        n %= 1_00_00_000;
        long lakh = n / 1_00_000;
        n %= 1_00_000;
        long thousand = n / 1_000;
        n %= 1_000;
        long hundred = n / 100;
        int rest = (int) (n % 100);
        if (crore > 0) {
            s.append(words(crore)).append(" Crore ");
        }
        if (lakh > 0) {
            s.append(below100((int) lakh)).append(" Lakh ");
        }
        if (thousand > 0) {
            s.append(below100((int) thousand)).append(" Thousand ");
        }
        if (hundred > 0) {
            s.append(ONES[(int) hundred]).append(" Hundred ");
        }
        if (rest > 0) {
            s.append(below100(rest));
        }
        return s.toString().strip();
    }

    private static String below100(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
    }
}
