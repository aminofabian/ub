package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class KenyaPayrollStatutoryCalculatorTest {

    @Test
    void zeroGrossReturnsZeroBreakdown() {
        var result = KenyaPayrollStatutoryCalculator.calculate(BigDecimal.ZERO);
        assertThat(result.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void lowWageStaffHasReliefWipingPaye() {
        var result = KenyaPayrollStatutoryCalculator.calculate(new BigDecimal("15000.00"));
        assertThat(result.nssf()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.shif()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.housingLevy()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.paye()).isEqualByComparingTo("0.00");
        assertThat(result.total()).isLessThan(new BigDecimal("15000.00"));
    }

    @Test
    void midWageStaffHasPaye() {
        var result = KenyaPayrollStatutoryCalculator.calculate(new BigDecimal("45000.00"));
        assertThat(result.paye()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.total()).isLessThan(new BigDecimal("45000.00"));
    }

    @Test
    void midWagePayeAppliesReliefAsCreditAfterBands() {
        // gross 45,000 → NSSF 2,160 · SHIF 1,237.50 · levy 675 → taxable 40,927.50
        // PAYE = 24,000×10% + 8,333×25% + 8,594.50×30% − 2,400 relief = 4,661.60
        var result = KenyaPayrollStatutoryCalculator.calculate(new BigDecimal("45000.00"));
        assertThat(result.nssf()).isEqualByComparingTo("2160.00");
        assertThat(result.shif()).isEqualByComparingTo("1237.50");
        assertThat(result.housingLevy()).isEqualByComparingTo("675.00");
        assertThat(result.paye()).isEqualByComparingTo("4661.60");
    }

    @Test
    void shifHasMinimumContribution() {
        // gross 10,000 → 2.75% = 275, floored to the KES 300 minimum
        var result = KenyaPayrollStatutoryCalculator.calculate(new BigDecimal("10000.00"));
        assertThat(result.shif()).isEqualByComparingTo("300.00");
    }

    @Test
    void topBandPayeMatchesKraBandsAndRelief() {
        // taxable 955,340 → 2,400 + 2,083.25 + 140,300.10 + 97,500 + 54,369 − 2,400 relief
        var result = KenyaPayrollStatutoryCalculator.calculate(new BigDecimal("1000000.00"));
        assertThat(result.paye()).isEqualByComparingTo("294252.35");
    }
}
