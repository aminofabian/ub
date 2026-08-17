package zelisline.ub.kplc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

class KplcDepletionEstimatorTest {

    @Test
    void estimatesEmptyFromHowLongPreviousSlipsLasted() {
        Instant t1 = LocalDateTime.of(2026, 8, 7, 12, 10).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 11, 19, 11).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 12, 15, 26).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant t4 = LocalDateTime.of(2026, 8, 16, 23, 26).atZone(KplcSpendStats.NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 17, 14, 40).atZone(KplcSpendStats.NAIROBI).toInstant();

        Optional<KplcDepletionEstimator.Estimate> estimate = KplcDepletionEstimator.estimate(List.of(
                token(t4, "10.7"),
                token(t3, "17.7"),
                token(t2, "3.6"),
                token(t1, "17.8")
        ), now);

        assertTrue(estimate.isPresent());
        assertFalse(estimate.get().alreadyEmpty());
        assertThat(estimate.get().dailyUseUnits()).isPositive();
        assertThat(estimate.get().remainingUnits()).isPositive();
        assertTrue(estimate.get().estimatedEmptyAt().isAfter(now));
        assertEquals(3, estimate.get().sampleIntervals());
    }

    @Test
    void needsTwoDatedPurchases() {
        Instant t1 = Instant.parse("2026-08-16T20:26:00Z");
        assertTrue(KplcDepletionEstimator.estimate(List.of(token(t1, "10.7")), t1).isEmpty());
    }

    @Test
    void alertCopyNamesTheWindow() {
        LocalDate emptyOn = LocalDate.of(2026, 8, 19);
        String two = KplcDepletionAlertService.buildMessage(
                "Palmart", "37156667398", 2, emptyOn, ZoneId.of("Africa/Nairobi"));
        assertTrue(two.contains("in 2 days"));
        assertTrue(two.contains("3715 6667 398"));
        String one = KplcDepletionAlertService.buildMessage(
                "Palmart", "37156667398", 1, emptyOn, ZoneId.of("Africa/Nairobi"));
        assertTrue(one.contains("tomorrow"));
    }

    private static PublicKplcTokenResponse token(Instant at, String units) {
        return new PublicKplcTokenResponse(
                at,
                new BigDecimal("100"),
                new BigDecimal(units),
                "1",
                null,
                "Cash",
                List.of());
    }
}
