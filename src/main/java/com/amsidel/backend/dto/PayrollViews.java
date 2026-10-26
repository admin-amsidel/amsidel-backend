package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.PayComponent;
import com.amsidel.backend.entity.PayComponentKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the payroll screens show. Money in rupees with 2 decimals. */
public final class PayrollViews {

    private PayrollViews() {
    }

    public record ComponentView(Long id, String code, String name, PayComponentKind kind, int sortOrder, boolean active,
                                boolean tax, boolean inUse) {
        public static ComponentView of(PayComponent c, boolean inUse) {
            return new ComponentView(c.getId(), c.getCode(), c.getName(), c.getKind(), c.getSortOrder(), c.isActive(),
                    c.isTax(), inUse);
        }
    }

    /** pan and bankAccount are payroll details: only payroll users and the person see them. */
    public record Person(Long id, String name, String empNumber, String designationName, String brandCode,
                         LocalDate joinedDate, boolean active, String photoPath, Instant photoUpdatedAt, String pan,
                         String bankAccount, String ifsc, String upi) {
        public static Person of(Employee e) {
            return new Person(e.getId(), e.getName(), e.getEmpNumber(),
                    e.getDesignation() == null ? null : e.getDesignation().getName(),
                    e.getBrand() == null ? null : e.getBrand().getCode(), e.getJoinedDate(), e.isActive(),
                    e.getPhotoPath(), e.getPhotoUpdatedAt(), e.getPanNumber(), e.getBankAccountNumber(), e.getIfscCode(),
                    e.getUpiId());
        }
    }

    /**
     * Totals of a salary. monthly*: one month; annual*: monthly x 12 (the yearly package);
     * year*: what is paid in this financial year, monthly x monthsInYear (from effectiveFrom to March).
     */
    public record Totals(BigDecimal monthlyGross, BigDecimal monthlyDeductions, BigDecimal monthlyReimbursements,
                         BigDecimal monthlyNet, BigDecimal annualGross, BigDecimal annualDeductions,
                         BigDecimal annualReimbursements, BigDecimal annualNet, int monthsInYear, BigDecimal yearGross,
                         BigDecimal yearNet) {
    }

    public record LineView(Long componentId, String code, String name, PayComponentKind kind, boolean active,
                           boolean tax, BigDecimal monthly, BigDecimal annual) {
    }

    /** One person in the payroll list; set = false when no salary is entered for this year yet. */
    public record SalaryRow(Person employee, boolean set, LocalDate effectiveFrom, int workingDays, Totals totals, boolean editable,
                            String updatedByName, Instant updatedAt, com.amsidel.backend.entity.TaxRegime taxRegime) {
    }

    /** A person's salary for a year, with a line for every component (active ones, plus any retired one it uses). */
    public record SalaryView(Person employee, int fyStartYear, String fyLabel, LocalDate fyStart, LocalDate fyEnd,
                             boolean set, LocalDate effectiveFrom, int workingDays, String notes, List<LineView> lines, Totals totals,
                             boolean editable, String updatedByName, Instant updatedAt, TaxView tax) {
    }

    /**
     * The salary's income tax: the regime, the old-regime declaration, the tax worked out under the regime chosen (null
     * for manual), both regimes side by side to compare, and whether I may type it by hand.
     */
    public record TaxView(com.amsidel.backend.entity.TaxRegime regime, BigDecimal declared, com.amsidel.backend.service.IncomeTax.Calc chosen,
                          com.amsidel.backend.service.IncomeTax.Calc newRegime, com.amsidel.backend.service.IncomeTax.Calc oldRegime,
                          boolean canManual, String taxComponentName) {
    }

    /** The payroll list for a year, with what it all costs. */
    public record YearView(int fyStartYear, String fyLabel, List<Integer> years, List<SalaryRow> rows, Totals totals,
                           int withSalary) {
    }
}
