package zelisline.ub.payroll.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simplified Kenya monthly statutory deductions for retail payroll (employee share).
 *
 * <p>Rates reflect common 2024/2025 practice: NSSF tiered pension, SHIF 2.75%, Housing Levy 1.5%,
 * PAYE on taxable pay after reliefs. Shops should verify with their accountant before filing.
 */
public final class KenyaPayrollStatutoryCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MONEY_SCALE = 2;

    private static final BigDecimal NSSF_TIER_I_CEILING = new BigDecimal("7000.00");
    private static final BigDecimal NSSF_TIER_II_CEILING = new BigDecimal("36000.00");
    private static final BigDecimal NSSF_RATE = new BigDecimal("0.06");
    private static final BigDecimal SHIF_RATE = new BigDecimal("0.0275");
    private static final BigDecimal HOUSING_LEVY_RATE = new BigDecimal("0.015");
    private static final BigDecimal PERSONAL_RELIEF = new BigDecimal("2400.00");

    private KenyaPayrollStatutoryCalculator() {
    }

    public record StatutoryBreakdown(
            BigDecimal nssf,
            BigDecimal shif,
            BigDecimal housingLevy,
            BigDecimal paye,
            BigDecimal total
    ) {
    }

    public static StatutoryBreakdown calculate(BigDecimal grossMonthly) {
        BigDecimal gross = money(grossMonthly);
        if (gross.signum() <= 0) {
            return zero();
        }

        BigDecimal nssf = money(calculateNssfEmployee(gross));
        BigDecimal shif = money(gross.multiply(SHIF_RATE));
        BigDecimal housing = money(gross.multiply(HOUSING_LEVY_RATE));
        BigDecimal taxable = gross.subtract(nssf).subtract(shif).subtract(housing).max(BigDecimal.ZERO);
        BigDecimal paye = money(calculatePaye(taxable));
        BigDecimal total = money(nssf.add(shif).add(housing).add(paye));
        return new StatutoryBreakdown(nssf, shif, housing, paye, total);
    }

    private static BigDecimal calculateNssfEmployee(BigDecimal gross) {
        BigDecimal tierI = gross.min(NSSF_TIER_I_CEILING).multiply(NSSF_RATE);
        BigDecimal tierIiBase = gross.min(NSSF_TIER_II_CEILING).subtract(NSSF_TIER_I_CEILING).max(BigDecimal.ZERO);
        BigDecimal tierII = tierIiBase.multiply(NSSF_RATE);
        return tierI.add(tierII);
    }

    private static BigDecimal calculatePaye(BigDecimal taxableMonthly) {
        BigDecimal remaining = taxableMonthly.subtract(PERSONAL_RELIEF).max(BigDecimal.ZERO);
        if (remaining.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal band1 = new BigDecimal("24000.00");
        BigDecimal band2 = new BigDecimal("8333.00");
        BigDecimal band3 = new BigDecimal("467667.00");
        BigDecimal band4 = new BigDecimal("300000.00");

        BigDecimal slice = remaining.min(band1);
        tax = tax.add(slice.multiply(new BigDecimal("0.10")));
        remaining = remaining.subtract(slice);
        if (remaining.signum() <= 0) {
            return money(tax);
        }

        slice = remaining.min(band2);
        tax = tax.add(slice.multiply(new BigDecimal("0.25")));
        remaining = remaining.subtract(slice);
        if (remaining.signum() <= 0) {
            return money(tax);
        }

        slice = remaining.min(band3);
        tax = tax.add(slice.multiply(new BigDecimal("0.30")));
        remaining = remaining.subtract(slice);
        if (remaining.signum() <= 0) {
            return money(tax);
        }

        slice = remaining.min(band4);
        tax = tax.add(slice.multiply(new BigDecimal("0.325")));
        remaining = remaining.subtract(slice);
        if (remaining.signum() > 0) {
            tax = tax.add(remaining.multiply(new BigDecimal("0.35")));
        }
        return money(tax);
    }

    private static StatutoryBreakdown zero() {
        BigDecimal z = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new StatutoryBreakdown(z, z, z, z, z);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
