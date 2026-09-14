package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import zelisline.ub.payroll.domain.JoinPayMode;

class SalaryProrationTest {

    @Test
    void resolveJoinDatePrefersStartDate() {
        assertThat(SalaryProration.resolveJoinDate(
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 1)
        )).isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    void fullModePaysFullEvenForMidMonthJoin() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 14), JoinPayMode.FULL);

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void halfModePaysHalfForMidMonthJoin() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 14), JoinPayMode.HALF);

        assertThat(result.payableAmount()).isEqualByComparingTo("6500.00");
        assertThat(result.prorationFactor()).isEqualByComparingTo("0.5");
    }

    @Test
    void halfModePaysFullWhenJoinedOnFirst() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 1), JoinPayMode.HALF);

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void prorateModeUsesCalendarDays() {
        // Sep 14–30 = 17 days → 17/30 × 13000 = 7366.67
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 14), JoinPayMode.PRORATE);

        assertThat(result.payableDays()).isEqualTo(17);
        assertThat(result.daysInMonth()).isEqualTo(30);
        assertThat(result.payableAmount()).isEqualByComparingTo("7366.67");
    }

    @Test
    void lockedZerosPayableButKeepsMonthly() {
        var open = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 14), JoinPayMode.HALF);
        var locked = open.locked();

        assertThat(locked.monthlyAmount()).isEqualByComparingTo("13000.00");
        assertThat(locked.payableAmount()).isEqualByComparingTo("0.00");
        assertThat(locked.prorationFactor()).isNull();
    }

    @Test
    void joinAfterMonthEndYieldsZero() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 10, 1), JoinPayMode.HALF);

        assertThat(result.payableAmount()).isEqualByComparingTo("0.00");
    }
}
