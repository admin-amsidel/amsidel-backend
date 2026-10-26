package com.amsidel.backend.service;

import com.amsidel.backend.entity.TaxRegime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Income tax on salary for an Indian financial year, under the new or the old regime (individuals below 60): slabs,
 * standard deduction, the section 87A rebate (with marginal relief), surcharge (with marginal relief) and 4% health and
 * education cess. Taxable income and the tax are rounded to the nearest ₹10 (sections 288A and 288B).
 * <ul>
 *   <li>New regime, FY 2025-26 on (Budget 2025): nil to 4 lakh, then 5/10/15/20/25% every 4 lakh, 30% above 24 lakh;
 *       standard deduction ₹75,000; no tax up to 12 lakh taxable (rebate up to ₹60,000).</li>
 *   <li>New regime, FY 2024-25: nil to 3 lakh, 5% to 7, 10% to 10, 15% to 12, 20% to 15, 30% above; ₹75,000;
 *       no tax up to 7 lakh.</li>
 *   <li>New regime, FY 2023-24 and before: nil to 3 lakh, 5% to 6, 10% to 9, 15% to 12, 20% to 15, 30% above;
 *       ₹50,000; no tax up to 7 lakh.</li>
 *   <li>Old regime: nil to 2.5 lakh, 5% to 5, 20% to 10, 30% above; ₹50,000; professional tax and the declared
 *       deductions (80C, 80D, HRA, home-loan interest...) come off; no tax up to 5 lakh (rebate up to ₹12,500).</li>
 * </ul>
 */
public final class IncomeTax {

    private IncomeTax() {
    }

    /** One way the tax was worked out, all yearly except monthlyTax. */
    public record Calc(TaxRegime regime, String rules, BigDecimal income, BigDecimal standardDeduction, BigDecimal professionalTax,
                       BigDecimal declared, BigDecimal taxable, BigDecimal slabTax, BigDecimal rebate, BigDecimal surcharge,
                       BigDecimal cess, BigDecimal annualTax, int months, BigDecimal monthlyTax) {
    }

    private record Slab(long upTo, int percent) {
    }

    private record Rules(String label, long standardDeduction, List<Slab> slabs, long rebateLimit, long rebateMax, boolean ptDeductible) {
    }

    private static final long INF = Long.MAX_VALUE;

    private static Rules rules(TaxRegime regime, int fyStartYear) {
        if (regime == TaxRegime.OLD) {
            return new Rules("Old regime", 50_000, List.of(new Slab(250_000, 0), new Slab(500_000, 5), new Slab(1_000_000, 20), new Slab(INF, 30)),
                    500_000, 12_500, true);
        }
        if (fyStartYear >= 2025) {
            return new Rules("New regime (FY 2025-26 rules)", 75_000, List.of(new Slab(400_000, 0), new Slab(800_000, 5), new Slab(1_200_000, 10),
                    new Slab(1_600_000, 15), new Slab(2_000_000, 20), new Slab(2_400_000, 25), new Slab(INF, 30)), 1_200_000, 60_000, false);
        }
        if (fyStartYear == 2024) {
            return new Rules("New regime (FY 2024-25 rules)", 75_000, List.of(new Slab(300_000, 0), new Slab(700_000, 5), new Slab(1_000_000, 10),
                    new Slab(1_200_000, 15), new Slab(1_500_000, 20), new Slab(INF, 30)), 700_000, 25_000, false);
        }
        return new Rules("New regime (FY 2023-24 rules)", 50_000, List.of(new Slab(300_000, 0), new Slab(600_000, 5), new Slab(900_000, 10),
                new Slab(1_200_000, 15), new Slab(1_500_000, 20), new Slab(INF, 30)), 700_000, 25_000, false);
    }

    /**
     * The tax on a year's salary.
     *
     * @param income          the gross earnings paid in the financial year
     * @param professionalTax professional tax paid in the year (comes off under the old regime)
     * @param declared        old regime: the declared deductions and exemptions for the year
     * @param months          the months the salary is paid in the year: the monthly tax is the yearly tax spread over them
     */
    public static Calc calc(TaxRegime regime, int fyStartYear, BigDecimal income, BigDecimal professionalTax, BigDecimal declared, int months) {
        if (regime == TaxRegime.MANUAL) {
            throw new IllegalArgumentException("Manual tax is typed in, not worked out");
        }
        Rules r = rules(regime, fyStartYear);
        BigDecimal gross = nz(income).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sd = gross.min(BigDecimal.valueOf(r.standardDeduction())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pt = r.ptDeductible() ? nz(professionalTax).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
        BigDecimal dec = regime == TaxRegime.OLD ? nz(declared).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
        BigDecimal taxable = round10(gross.subtract(sd).subtract(pt).subtract(dec).max(BigDecimal.ZERO));

        BigDecimal slab = slabTax(r, taxable);
        // 87A: no tax up to the limit; just above it, the tax is at most what the income is over the limit
        BigDecimal rebate = BigDecimal.ZERO;
        BigDecimal limit = BigDecimal.valueOf(r.rebateLimit());
        if (taxable.compareTo(limit) <= 0) {
            rebate = slab.min(BigDecimal.valueOf(r.rebateMax()));
        } else if (regime == TaxRegime.NEW) {
            BigDecimal over = taxable.subtract(limit);
            if (slab.compareTo(over) > 0) {
                rebate = slab.subtract(over);
            }
        }
        BigDecimal afterRebate = slab.subtract(rebate).max(BigDecimal.ZERO);
        BigDecimal surcharge = surcharge(r, regime, taxable, afterRebate);
        BigDecimal cess = afterRebate.add(surcharge).multiply(BigDecimal.valueOf(4)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal annual = round10(afterRebate.add(surcharge).add(cess));
        int m = Math.max(months, 1);
        BigDecimal monthly = annual.divide(BigDecimal.valueOf(m), 0, RoundingMode.HALF_UP).setScale(2);
        return new Calc(regime, r.label(), gross, sd, pt, dec, taxable, slab, rebate, surcharge, cess, annual, months, monthly);
    }

    private static BigDecimal slabTax(Rules r, BigDecimal taxable) {
        BigDecimal tax = BigDecimal.ZERO;
        long below = 0;
        for (Slab s : r.slabs()) {
            if (taxable.compareTo(BigDecimal.valueOf(below)) <= 0) {
                break;
            }
            BigDecimal top = s.upTo() == INF ? taxable : taxable.min(BigDecimal.valueOf(s.upTo()));
            BigDecimal part = top.subtract(BigDecimal.valueOf(below));
            tax = tax.add(part.multiply(BigDecimal.valueOf(s.percent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            below = s.upTo() == INF ? below : s.upTo();
            if (s.upTo() == INF) {
                break;
            }
        }
        return tax.setScale(2, RoundingMode.HALF_UP);
    }

    /** 10% above 50 lakh, 15% above 1 crore, 25% above 2 crore, 37% above 5 crore (old regime only); marginal relief at each step. */
    private static BigDecimal surcharge(Rules r, TaxRegime regime, BigDecimal taxable, BigDecimal tax) {
        long[][] steps = regime == TaxRegime.OLD
                ? new long[][] {{50_000_000, 37}, {20_000_000, 25}, {10_000_000, 15}, {5_000_000, 10}}
                : new long[][] {{20_000_000, 25}, {10_000_000, 15}, {5_000_000, 10}};
        for (long[] step : steps) {
            BigDecimal threshold = BigDecimal.valueOf(step[0]);
            if (taxable.compareTo(threshold) > 0) {
                BigDecimal sur = tax.multiply(BigDecimal.valueOf(step[1])).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                // marginal relief: tax + surcharge may not go over the tax at the threshold (with its own surcharge) + the income above it
                BigDecimal atThreshold = slabTax(r, threshold);
                BigDecimal lowerRate = BigDecimal.ZERO;
                for (long[] lower : steps) {
                    if (lower[0] < step[0] && threshold.compareTo(BigDecimal.valueOf(lower[0])) > 0) {
                        lowerRate = BigDecimal.valueOf(lower[1]);
                        break;
                    }
                }
                BigDecimal cap = atThreshold.add(atThreshold.multiply(lowerRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                        .add(taxable.subtract(threshold));
                BigDecimal total = tax.add(sur);
                return total.compareTo(cap) > 0 ? cap.subtract(tax).max(BigDecimal.ZERO) : sur;
            }
        }
        return BigDecimal.ZERO.setScale(2);
    }

    private static BigDecimal round10(BigDecimal v) {
        return v.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN).setScale(2);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
