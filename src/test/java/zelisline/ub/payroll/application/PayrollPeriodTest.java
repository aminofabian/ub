package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PayrollPeriodTest {

    @Test
    void septemberRunsFromAugust25ThroughSeptember24() {
        var bounds = PayrollPeriod.bounds(2026, 9);

        assertThat(bounds.start()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(bounds.end()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(bounds.dayCount()).isEqualTo(31);
        assertThat(PayrollPeriod.asOf(2026, 9)).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void januaryRunsFromDecember25ThroughJanuary24() {
        var bounds = PayrollPeriod.bounds(2026, 1);

        assertThat(bounds.start()).isEqualTo(LocalDate.of(2025, 12, 25));
        assertThat(bounds.end()).isEqualTo(LocalDate.of(2026, 1, 24));
        assertThat(bounds.dayCount()).isEqualTo(31);
    }

    @Test
    void februaryCycleUsesFebruary24() {
        var bounds = PayrollPeriod.bounds(2026, 2);

        assertThat(bounds.start()).isEqualTo(LocalDate.of(2026, 1, 25));
        assertThat(bounds.end()).isEqualTo(LocalDate.of(2026, 2, 24));
        assertThat(bounds.dayCount()).isEqualTo(31);
    }
}
