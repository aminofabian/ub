package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class WorkingHoursTest {

    // 2026-11-23 is a Monday; 2026-11-28 is a Saturday; 2026-11-29 is a Sunday.
    private static Instant nairobi(String local) {
        return LocalDateTime.parse(local).atZone(WorkingHours.NAIROBI).toInstant();
    }

    @Test
    void countsOnlyInsideOpeningHours() {
        assertThat(WorkingHours.between(nairobi("2026-11-23T09:00"), nairobi("2026-11-23T11:30")))
                .isEqualTo(2.5);
        assertThat(WorkingHours.between(nairobi("2026-11-23T07:00"), nairobi("2026-11-23T19:00")))
                .isEqualTo(10.0);
        assertThat(WorkingHours.between(nairobi("2026-11-23T05:00"), nairobi("2026-11-23T07:00")))
                .isEqualTo(0.0);
    }

    @Test
    void skipsSundays() {
        assertThat(WorkingHours.between(nairobi("2026-11-29T08:00"), nairobi("2026-11-29T18:00")))
                .isEqualTo(0.0);
        // Friday 17:00 to Monday 09:00: 1h Friday, 10h Saturday, 1h Monday.
        assertThat(WorkingHours.between(nairobi("2026-11-27T17:00"), nairobi("2026-11-30T09:00")))
                .isEqualTo(12.0);
    }

    @Test
    void zeroWhenReversedOrMissing() {
        assertThat(WorkingHours.between(nairobi("2026-11-23T11:00"), nairobi("2026-11-23T09:00"))).isEqualTo(0.0);
        assertThat(WorkingHours.between(null, nairobi("2026-11-23T09:00"))).isEqualTo(0.0);
    }

    @Test
    void publicHolidaysDoNotCount() {
        // Christmas 2026 (Friday), Good Friday 3 April 2026, Easter Monday 6 April 2026: closed.
        assertThat(WorkingHours.between(nairobi("2026-12-25T08:00"), nairobi("2026-12-25T18:00"))).isEqualTo(0.0);
        assertThat(WorkingHours.between(nairobi("2026-04-03T08:00"), nairobi("2026-04-03T18:00"))).isEqualTo(0.0);
        assertThat(WorkingHours.between(nairobi("2026-04-06T08:00"), nairobi("2026-04-06T18:00"))).isEqualTo(0.0);
        // The Tuesday after Easter Monday is open.
        assertThat(WorkingHours.between(nairobi("2026-04-07T08:00"), nairobi("2026-04-07T18:00"))).isEqualTo(10.0);
        // Idd-ul-Fitr 2026 was gazetted for Friday 20 March.
        assertThat(WorkingHours.between(nairobi("2026-03-20T08:00"), nairobi("2026-03-20T18:00"))).isEqualTo(0.0);
    }

    @Test
    void aSuppliersOwnClosedDaysDoNotCount() {
        // Tuesday 24 November 2026 is closed for this supplier.
        assertThat(WorkingHours.between(nairobi("2026-11-24T08:00"), nairobi("2026-11-24T18:00"),
                java.util.Set.of(java.time.LocalDate.of(2026, 11, 24)))).isEqualTo(0.0);
        assertThat(WorkingHours.between(nairobi("2026-11-23T08:00"), nairobi("2026-11-23T18:00"),
                java.util.Set.of(java.time.LocalDate.of(2026, 11, 24)))).isEqualTo(10.0);
    }

    @Test
    void medianOfOddAndEvenCounts() {
        assertThat(WorkingHours.median(List.of(1.0, 5.0, 3.0))).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(WorkingHours.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(WorkingHours.median(List.of())).isNull();
    }
}
