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
}
