/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * The kinds of other income and expense the portal started with: added once to money_types (MoneyType), where owners
 * and admins rename them, switch them off and add their own. Entries keep these codes.
 */
public enum MoneyCategory {
    INVESTMENT(true, true, "Investment from a person"),
    DIRECTOR_LOAN(true, true, "Investor / director loan"),
    CAPITAL(true, true, "Capital investment"),
    BANK_LOAN(true, true, "Bank loan"),
    OTHER_INCOME(true, false, "Other income"),

    TRANSPORT(false, false, "Transportation"),
    FIXING(false, false, "Fixing / installation"),
    MAINTENANCE(false, false, "Maintenance / service"),
    REFUND(false, false, "Refund outside an invoice"),
    DISCOUNT(false, false, "Discount allowed / short payment"),
    RENT(false, false, "Rent"),
    UTILITIES(false, false, "Electricity, phone & internet"),
    BANK_CHARGES(false, false, "Bank charges"),
    LOAN_REPAYMENT(false, true, "Loan repayment"),
    MISC(false, false, "Miscellaneous expense");

    private final boolean income;
    private final boolean funding;
    private final String label;

    MoneyCategory(boolean income, boolean funding, String label) {
        this.income = income;
        this.funding = funding;
        this.label = label;
    }

    /** Money in (else money out). */
    public boolean isIncome() {
        return income;
    }

    /** Investment, loans and repayments: in the bank tally, not in the profit and loss. */
    public boolean isFunding() {
        return funding;
    }

    public String getLabel() {
        return label;
    }
}
