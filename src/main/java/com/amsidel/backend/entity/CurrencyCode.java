/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * The currency a brand shows its money in, on invoices, quotations and payslips: only the symbol and the words change,
 * there is no conversion. Rupees are grouped the Indian way (2,14,945.00), the others the international way.
 */
public enum CurrencyCode {
    INR("₹", "Indian Rupee", "Paise", true, 2),
    USD("$", "US Dollar", "Cents", false, 2),
    EUR("€", "Euro", "Cents", false, 2),
    GBP("£", "British Pound", "Pence", false, 2),
    /** No smaller unit: whole yen. */
    JPY("¥", "Japanese Yen", null, false, 0),
    AED("AED", "UAE Dirham", "Fils", false, 2),
    AUD("A$", "Australian Dollar", "Cents", false, 2),
    NZD("NZ$", "New Zealand Dollar", "Cents", false, 2),
    /** CN¥, so it is not taken for the yen. */
    CNY("CN¥", "Chinese Yuan Renminbi", "Fen", false, 2),
    CHF("CHF", "Swiss Franc", "Rappen", false, 2),
    CAD("CA$", "Canadian Dollar", "Cents", false, 2);

    private final String symbol;
    private final String unit;
    private final String subunit;
    private final boolean indianGrouping;
    private final int decimals;

    CurrencyCode(String symbol, String unit, String subunit, boolean indianGrouping, int decimals) {
        this.symbol = symbol;
        this.unit = unit;
        this.subunit = subunit;
        this.indianGrouping = indianGrouping;
        this.decimals = decimals;
    }

    /** 2, or 0 for a currency without a smaller unit (yen). */
    public int decimals() {
        return decimals;
    }

    public String symbol() {
        return symbol;
    }

    /** "Indian Rupee", "US Dollar": as it starts the amount in words. */
    public String unit() {
        return unit;
    }

    public String subunit() {
        return subunit;
    }

    public boolean indianGrouping() {
        return indianGrouping;
    }

    /** null or unknown = rupees. */
    public static CurrencyCode of(String code) {
        if (code == null) {
            return INR;
        }
        try {
            return valueOf(code.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INR;
        }
    }
}
