package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PayrollPeriodTest {

    @Test
    void septemberBoundsAreCalendarMonth() {
        var bounds = PayrollPeriod.bounds(2026, 9);

        assertThat(bounds.start()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(bounds.end()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(bounds.dayCount()).isEqualTo(30);
        assertThat(PayrollPeriod.asOf(2026, 9)).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void unlocksOnThe25th() {
        assertThat(PayrollPeriod.unlockDate(2026, 9)).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(PayrollPeriod.isReleased(2026, 9, LocalDate.of(2026, 9, 14))).isFalse();
        assertThat(PayrollPeriod.isReleased(2026, 9, LocalDate.of(2026, 9, 25))).isTrue();
        assertThat(PayrollPeriod.isReleased(2026, 8, LocalDate.of(2026, 9, 14))).isTrue();
    }
}
