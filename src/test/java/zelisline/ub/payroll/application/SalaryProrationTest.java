package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SalaryProrationTest {

    @Test
    void resolveJoinDatePrefersStartDate() {
        assertThat(SalaryProration.resolveJoinDate(
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 1)
        )).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void resolveJoinDateFallsBackToSalaryEffectiveFrom() {
        assertThat(SalaryProration.resolveJoinDate(null, LocalDate.of(2026, 9, 15)))
                .isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void fullPeriodWhenJoinIsOnCycleStart() {
        // Sep period = Aug 25–Sep 24 (31 days)
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 8, 25));

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.monthlyAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
        assertThat(result.payableDays()).isEqualTo(31);
        assertThat(result.isProrated()).isFalse();
    }

    @Test
    void fullPeriodWhenJoinIsBeforeCycle() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 8, 20));

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void fullPeriodWhenJoinDateIsNull() {
        var result = SalaryProration.prorate(new BigDecimal("13000.00"), 2026, 9, null);

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void midCycleStartOnFifteenthInSeptember() {
        // Sep period Aug 25–Sep 24; join Sep 15 → Sep 15–24 = 10 days → 10/31 × 13000
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 15));

        assertThat(result.payableDays()).isEqualTo(10);
        assertThat(result.daysInMonth()).isEqualTo(31);
        assertThat(result.payableAmount()).isEqualByComparingTo("4193.55");
        assertThat(result.isProrated()).isTrue();
    }

    @Test
    void joinOnCycleStartOfNextPeriodYieldsZeroForThisPeriod() {
        // Sep 25 starts the October cycle — not payable in September
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 25));

        assertThat(result.payableAmount()).isEqualByComparingTo("0.00");
        assertThat(result.payableDays()).isZero();
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void joinOnSeptember25IsFullOctoberCycleStart() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 10, LocalDate.of(2026, 9, 25));

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void zeroOrNullAmountReturnsNone() {
        assertThat(SalaryProration.prorate(BigDecimal.ZERO, 2026, 9, LocalDate.of(2026, 9, 15))
                .payableAmount()).isEqualByComparingTo("0.00");
        assertThat(SalaryProration.prorate(null, 2026, 9, LocalDate.of(2026, 9, 15))
                .payableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void overrideDisabledPaysFullPeriodForMidCycleJoin() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"),
                2026,
                9,
                LocalDate.of(2026, 9, 15),
                false
        );

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
        assertThat(result.isProrated()).isFalse();
    }

    @Test
    void overrideDisabledStillZeroWhenJoinAfterPeriodEnd() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"),
                2026,
                9,
                LocalDate.of(2026, 9, 25),
                false
        );

        assertThat(result.payableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void applyEnabledMatchesProrate() {
        var enabled = SalaryProration.apply(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 15), true);
        var direct = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 15));

        assertThat(enabled.payableAmount()).isEqualByComparingTo(direct.payableAmount());
        assertThat(enabled.prorationFactor()).isEqualByComparingTo(direct.prorationFactor());
    }
}
