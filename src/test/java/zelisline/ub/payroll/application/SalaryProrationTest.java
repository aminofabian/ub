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
    void fullMonthWhenJoinIsOnFirst() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 1));

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.monthlyAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
        assertThat(result.payableDays()).isEqualTo(30);
        assertThat(result.isProrated()).isFalse();
    }

    @Test
    void fullMonthWhenJoinIsBeforePeriod() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 8, 20));

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void fullMonthWhenJoinDateIsNull() {
        var result = SalaryProration.prorate(new BigDecimal("13000.00"), 2026, 9, null);

        assertThat(result.payableAmount()).isEqualByComparingTo("13000.00");
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void midMonthStartOnFifteenthInSeptember() {
        // Sep 15–30 = 16 days → 16/30 × 13000 = 6933.33
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 15));

        assertThat(result.payableDays()).isEqualTo(16);
        assertThat(result.daysInMonth()).isEqualTo(30);
        assertThat(result.payableAmount()).isEqualByComparingTo("6933.33");
        assertThat(result.prorationFactor()).isEqualByComparingTo("0.53333333");
        assertThat(result.isProrated()).isTrue();
    }

    @Test
    void midMonthStartOnSixteenthIsExactlyHalfInSeptember() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 9, 16));

        assertThat(result.payableDays()).isEqualTo(15);
        assertThat(result.payableAmount()).isEqualByComparingTo("6500.00");
    }

    @Test
    void startAfterMonthEndYieldsZero() {
        var result = SalaryProration.prorate(
                new BigDecimal("13000.00"), 2026, 9, LocalDate.of(2026, 10, 1));

        assertThat(result.payableAmount()).isEqualByComparingTo("0.00");
        assertThat(result.payableDays()).isZero();
        assertThat(result.prorationFactor()).isNull();
    }

    @Test
    void thirtyOneDayMonthProratesCorrectly() {
        // Jul 16–31 = 16 days → 16/31 × 31000 = 16000.00
        var result = SalaryProration.prorate(
                new BigDecimal("31000.00"), 2026, 7, LocalDate.of(2026, 7, 16));

        assertThat(result.daysInMonth()).isEqualTo(31);
        assertThat(result.payableDays()).isEqualTo(16);
        assertThat(result.payableAmount()).isEqualByComparingTo("16000.00");
    }

    @Test
    void zeroOrNullAmountReturnsNone() {
        assertThat(SalaryProration.prorate(BigDecimal.ZERO, 2026, 9, LocalDate.of(2026, 9, 15))
                .payableAmount()).isEqualByComparingTo("0.00");
        assertThat(SalaryProration.prorate(null, 2026, 9, LocalDate.of(2026, 9, 15))
                .payableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void overrideDisabledPaysFullMonthForMidMonthJoin() {
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
    void overrideDisabledStillZeroWhenJoinAfterMonthEnd() {
        var result = SalaryProration.apply(
                new BigDecimal("13000.00"),
                2026,
                9,
                LocalDate.of(2026, 10, 1),
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
