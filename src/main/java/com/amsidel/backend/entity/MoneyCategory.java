package com.amsidel.backend.entity;

/**
 * What money that is not a customer or supplier payment, nor a salary, was for. Money in or out; funding (investment,
 * loans and their repayment) moves the bank balance but is not profit or loss.
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
