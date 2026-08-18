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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

class KplcDepletionEstimatorTest {

    private static final ZoneId NAIROBI = KplcSpendStats.NAIROBI;

    @Test
    void doesNotTreatIdleDaysAfterLastBuyAsExtraConsumptionHistory() {
        Instant t1 = LocalDateTime.of(2026, 7, 28, 12, 0).atZone(NAIROBI).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 6, 12, 0).atZone(NAIROBI).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 10, 12, 0).atZone(NAIROBI).toInstant();
        Instant t4 = LocalDateTime.of(2026, 8, 15, 12, 0).atZone(NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 18, 22, 0).atZone(NAIROBI).toInstant();

        KplcDepletionEstimator.Estimate got = KplcDepletionEstimator.estimate(List.of(
                token(t1, "53.2"),
                token(t2, "28.4"),
                token(t3, "35.4"),
                token(t4, "53.1")
        ), now).orElseThrow();

        assertFalse(got.alreadyEmpty());
        assertThat(got.lastPurchaseUnits()).isEqualByComparingTo("53.1");
        assertThat(got.dailyUseUnits()).isBetween(new BigDecimal("6.0"), new BigDecimal("7.0"));
        assertThat(got.remainingUnits()).isBetween(new BigDecimal("28.0"), new BigDecimal("42.0"));
        assertThat(got.lastPurchaseUnits().subtract(got.remainingUnits()))
                .isGreaterThan(new BigDecimal("12"));
    }

    @Test
    void estimatesEmptyFromHowLongPreviousSlipsLasted() {
        Instant t1 = LocalDateTime.of(2026, 8, 7, 12, 10).atZone(NAIROBI).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 11, 19, 11).atZone(NAIROBI).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 12, 15, 26).atZone(NAIROBI).toInstant();
        Instant t4 = LocalDateTime.of(2026, 8, 16, 23, 26).atZone(NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 17, 14, 40).atZone(NAIROBI).toInstant();

        Optional<KplcDepletionEstimator.Estimate> estimate = KplcDepletionEstimator.estimate(List.of(
                token(t4, "10.7"),
                token(t3, "17.7"),
                token(t2, "3.6"),
                token(t1, "17.8")
        ), now);

        assertTrue(estimate.isPresent());
        KplcDepletionEstimator.Estimate got = estimate.get();
        assertFalse(got.alreadyEmpty());
        assertThat(got.dailyUseUnits()).isBetween(new BigDecimal("3.5"), new BigDecimal("4.6"));
        assertThat(got.remainingUnits()).isBetween(new BigDecimal("5.0"), new BigDecimal("14.0"));
        assertTrue(got.estimatedEmptyAt().isAfter(now));
        assertTrue(got.estimatedEmptyAt().isBefore(
                LocalDateTime.of(2026, 8, 21, 0, 0).atZone(NAIROBI).toInstant()));
        assertEquals(4, got.sampleIntervals());
        assertThat(got.lastPurchaseUnits()).isEqualByComparingTo("10.7");
    }

    @Test
    void carriesLeftoverWhenYouBuyBeforeTheMeterIsEmpty() {
        Instant t1 = LocalDateTime.of(2026, 8, 1, 8, 0).atZone(NAIROBI).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 6, 8, 0).atZone(NAIROBI).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 7, 8, 0).atZone(NAIROBI).toInstant();
        Instant t4 = LocalDateTime.of(2026, 8, 8, 8, 0).atZone(NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 8, 20, 0).atZone(NAIROBI).toInstant();

        KplcDepletionEstimator.Estimate got = KplcDepletionEstimator.estimate(List.of(
                token(t1, "20"),
                token(t2, "20"),
                token(t3, "20"),
                token(t4, "20")
        ), now).orElseThrow();

        assertFalse(got.alreadyEmpty());
        assertThat(got.dailyUseUnits()).isBetween(new BigDecimal("7.0"), new BigDecimal("9.5"));
        assertThat(got.carryInUnits()).isGreaterThan(new BigDecimal("10"));
        assertThat(got.remainingUnits()).isGreaterThan(new BigDecimal("30"));
        assertTrue(got.estimatedEmptyAt().isAfter(
                LocalDateTime.of(2026, 8, 12, 0, 0).atZone(NAIROBI).toInstant()));
        assertEquals(4, got.sampleIntervals());
    }

    @Test
    void usesLastFiveTokensAsOneHourlySpend() {
        Instant now = LocalDateTime.of(2026, 7, 24, 7, 0).atZone(NAIROBI).toInstant();
        KplcDepletionEstimator.Estimate got = KplcDepletionEstimator.estimate(List.of(
                token(LocalDateTime.of(2026, 1, 1, 8, 0).atZone(NAIROBI).toInstant(), "100"),
                token(LocalDateTime.of(2026, 6, 12, 7, 0).atZone(NAIROBI).toInstant(), "10"),
                token(LocalDateTime.of(2026, 6, 22, 7, 0).atZone(NAIROBI).toInstant(), "10"),
                token(LocalDateTime.of(2026, 7, 2, 7, 0).atZone(NAIROBI).toInstant(), "10"),
                token(LocalDateTime.of(2026, 7, 12, 7, 0).atZone(NAIROBI).toInstant(), "10"),
                token(LocalDateTime.of(2026, 7, 22, 7, 0).atZone(NAIROBI).toInstant(), "10")
        ), now).orElseThrow();

        assertEquals(5, got.sampleIntervals());
        assertThat(got.dailyUseUnits()).isBetween(new BigDecimal("0.8"), new BigDecimal("1.2"));
    }

    @Test
    void emptyClockLeansEveningNotAFlatDrip() {
        Instant buy = LocalDateTime.of(2026, 8, 18, 7, 0).atZone(NAIROBI).toInstant();
        Instant now = buy;
        KplcDepletionEstimator.Estimate got = KplcDepletionEstimator.estimate(List.of(
                token(buy.minusSeconds(4 * 86_400), "16"),
                token(buy, "3")
        ), now).orElseThrow();

        assertThat(got.dailyUseUnits()).isBetween(new BigDecimal("3.5"), new BigDecimal("4.5"));
        ZonedDateTime empty = got.estimatedEmptyAt().atZone(NAIROBI);
        assertEquals(18, empty.getDayOfMonth());
        assertThat(empty.getHour()).isGreaterThanOrEqualTo(16);
    }

    @Test
    void emptyAtDoesNotJumpWhenYouRefreshAnHourLater() {
        Instant t1 = LocalDateTime.of(2026, 8, 10, 7, 0).atZone(NAIROBI).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 14, 7, 0).atZone(NAIROBI).toInstant();
        Instant now = LocalDateTime.of(2026, 8, 15, 10, 0).atZone(NAIROBI).toInstant();
        Instant later = LocalDateTime.of(2026, 8, 15, 11, 0).atZone(NAIROBI).toInstant();

        KplcDepletionEstimator.Estimate first = KplcDepletionEstimator.estimate(
                List.of(token(t1, "16"), token(t2, "16")), now)
                .orElseThrow();
        KplcDepletionEstimator.Estimate second = KplcDepletionEstimator.estimate(
                List.of(token(t1, "16"), token(t2, "16")), later)
                .orElseThrow();
        assertFalse(first.alreadyEmpty());
        assertFalse(second.alreadyEmpty());
        long driftMinutes = Math.abs(DurationBetweenMinutes(first.estimatedEmptyAt(), second.estimatedEmptyAt()));
        assertThat(driftMinutes).isLessThan(180);
    }

    @Test
    void needsTwoDatedPurchases() {
        Instant t1 = Instant.parse("2026-08-16T20:26:00Z");
        assertTrue(KplcDepletionEstimator.estimate(List.of(token(t1, "10.7")), t1).isEmpty());
    }

    @Test
    void hourSharesCoverAFullDay() {
        double sum = 0;
        for (int hour = 0; hour < 24; hour++) {
            sum += KplcDepletionEstimator.hourShare(hour);
        }
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(KplcDepletionEstimator.hourShare(19))
                .isGreaterThan(KplcDepletionEstimator.hourShare(3));
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
        assertEquals(
                "KPLC meter 3715 6667 398 looks like it runs out tomorrow (Wed 19 Aug). Buy a token before the lights go.",
                KplcDepletionAlertService.stripShopPrefix(one, "Palmart"));
    }

    private static long DurationBetweenMinutes(Instant a, Instant b) {
        return Math.round(Math.abs(a.toEpochMilli() - b.toEpochMilli()) / 60_000.0);
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
