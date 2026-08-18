package zelisline.ub.kplc.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

/**
 * Estimates remaining kWh and when the meter goes dark.
 *
 * <p>Daily use comes from how long <em>full cycles</em> lasted (buys at least
 * 36 hours apart). Panic top-ups and stacked slips are left out of the rate so
 * they cannot look like a 20 kWh/day house.
 *
 * <p>Stock walks forward: leftover from an early buy is added to the next
 * token instead of assuming the tank was empty. From the last buy, kWh burn
 * follows a Nairobi household shape — more in the evening, less after
 * midnight — so the empty clock is not a flat 24-hour drip.
 *
 * <p>This is not a live meter read.
 */
final class KplcDepletionEstimator {

    static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    /** Skip lookup glitches / duplicate stamps. */
    private static final Duration MIN_INTERVAL = Duration.ofHours(6);
    /** Gaps shorter than this are top-ups, not a finished cycle. */
    private static final Duration MIN_CYCLE = Duration.ofHours(36);
    private static final int RATE_SCALE = 4;
    private static final int DISPLAY_SCALE = 1;
    private static final double EMPTY_EPS = 0.05;

    /**
     * Relative kWh by hour of day in Africa/Nairobi. Night fridge, morning
     * kettle/iron, quiet midday, evening lights/TV/cooking. Sum is 822.
     */
    static final int[] HOUR_WEIGHT = {
            14, 12, 11, 10, 11, 14,
            32, 46, 50,
            34, 28, 24, 22, 22, 24, 28, 34,
            56, 72, 78, 74, 60,
            40, 26
    };
    static final int HOUR_WEIGHT_SUM = 822;

    private KplcDepletionEstimator() {
    }

    record Estimate(
            Instant estimatedEmptyAt,
            BigDecimal remainingUnits,
            BigDecimal lastPurchaseUnits,
            BigDecimal dailyUseUnits,
            int sampleIntervals,
            boolean alreadyEmpty,
            BigDecimal carryInUnits
    ) {
    }

    static Optional<Estimate> estimate(List<PublicKplcTokenResponse> tokens, Instant now) {
        if (tokens == null || tokens.isEmpty() || now == null) {
            return Optional.empty();
        }
        List<PublicKplcTokenResponse> dated = tokens.stream()
                .filter(t -> t != null
                        && t.purchasedAt() != null
                        && t.units() != null
                        && t.units().signum() > 0)
                .sorted(Comparator.comparing(PublicKplcTokenResponse::purchasedAt))
                .toList();
        if (dated.size() < 2) {
            return Optional.empty();
        }

        List<BigDecimal> cycleRates = new ArrayList<>();
        List<BigDecimal> anyRates = new ArrayList<>();
        for (int i = 0; i < dated.size() - 1; i++) {
            Instant start = dated.get(i).purchasedAt();
            Instant end = dated.get(i + 1).purchasedAt();
            Duration gap = Duration.between(start, end);
            if (gap.compareTo(MIN_INTERVAL) < 0) {
                continue;
            }
            double days = gap.toMillis() / 86_400_000.0;
            if (days <= 0) {
                continue;
            }
            BigDecimal rate = dated.get(i).units()
                    .divide(BigDecimal.valueOf(days), RATE_SCALE, RoundingMode.HALF_UP);
            if (rate.signum() <= 0) {
                continue;
            }
            anyRates.add(rate);
            if (gap.compareTo(MIN_CYCLE) >= 0) {
                cycleRates.add(rate);
            }
        }
        List<BigDecimal> rates = !cycleRates.isEmpty() ? cycleRates : anyRates;
        if (rates.isEmpty()) {
            Instant first = dated.getFirst().purchasedAt();
            Instant last = dated.getLast().purchasedAt();
            Duration span = Duration.between(first, last);
            if (span.compareTo(MIN_INTERVAL) < 0) {
                return Optional.empty();
            }
            BigDecimal consumed = BigDecimal.ZERO;
            for (int i = 0; i < dated.size() - 1; i++) {
                consumed = consumed.add(dated.get(i).units());
            }
            if (consumed.signum() <= 0) {
                return Optional.empty();
            }
            double days = span.toMillis() / 86_400_000.0;
            rates.add(consumed.divide(BigDecimal.valueOf(days), RATE_SCALE, RoundingMode.HALF_UP));
        }
        BigDecimal dailyBd = median(rates);
        if (dailyBd == null || dailyBd.signum() <= 0) {
            return Optional.empty();
        }
        double daily = dailyBd.doubleValue();
        PublicKplcTokenResponse latest = dated.getLast();
        double lastUnits = latest.units().doubleValue();

        double stock = 0;
        for (int i = 0; i < dated.size(); i++) {
            stock += dated.get(i).units().doubleValue();
            if (i < dated.size() - 1) {
                stock = consume(
                        dated.get(i).purchasedAt(),
                        dated.get(i + 1).purchasedAt(),
                        stock,
                        daily,
                        ZONE);
            }
        }
        double carryIn = Math.max(0, stock - lastUnits);
        double remaining = consume(latest.purchasedAt(), now, stock, daily, ZONE);
        boolean alreadyEmpty = remaining <= EMPTY_EPS;
        Instant emptyAt;
        if (alreadyEmpty) {
            remaining = 0;
            emptyAt = whenEmpty(latest.purchasedAt(), stock, daily, ZONE);
            if (emptyAt.isAfter(now)) {
                emptyAt = now;
            }
        } else {
            emptyAt = whenEmpty(now, remaining, daily, ZONE);
        }
        return Optional.of(new Estimate(
                emptyAt,
                BigDecimal.valueOf(remaining).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                latest.units(),
                dailyBd,
                rates.size(),
                alreadyEmpty,
                BigDecimal.valueOf(carryIn).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)));
    }

    static double hourShare(int hour) {
        int h = Math.floorMod(hour, 24);
        return HOUR_WEIGHT[h] / (double) HOUR_WEIGHT_SUM;
    }

    static double consume(Instant from, Instant to, double stock, double daily, ZoneId zone) {
        if (stock <= 0 || daily <= 0 || from == null || to == null || !to.isAfter(from)) {
            return Math.max(0, stock);
        }
        Instant t = from;
        double rem = stock;
        int guard = 0;
        while (rem > 1e-9 && t.isBefore(to) && guard++ < 24 * 200) {
            ZonedDateTime z = t.atZone(zone);
            double hourUse = daily * hourShare(z.getHour());
            long msLeftInHour = millisLeftInHour(z);
            if (msLeftInHour <= 0) {
                t = t.plusMillis(1);
                continue;
            }
            long msStep = Math.min(msLeftInHour, Duration.between(t, to).toMillis());
            if (msStep <= 0) {
                break;
            }
            double use = hourUse * (msStep / 3_600_000.0);
            if (use >= rem) {
                return 0;
            }
            rem -= use;
            t = t.plusMillis(msStep);
        }
        return Math.max(0, rem);
    }

    static Instant whenEmpty(Instant from, double stock, double daily, ZoneId zone) {
        if (from == null || stock <= 1e-9 || daily <= 0) {
            return from;
        }
        Instant t = from;
        double rem = stock;
        int guard = 0;
        Instant cap = from.plus(200, ChronoUnit.DAYS);
        while (rem > 1e-9 && t.isBefore(cap) && guard++ < 24 * 200) {
            ZonedDateTime z = t.atZone(zone);
            double hourUse = daily * hourShare(z.getHour());
            long msLeftInHour = millisLeftInHour(z);
            if (msLeftInHour <= 0) {
                t = t.plusMillis(1);
                continue;
            }
            double useFull = hourUse * (msLeftInHour / 3_600_000.0);
            if (useFull >= rem) {
                if (hourUse <= 0) {
                    t = t.plusMillis(msLeftInHour);
                    continue;
                }
                long add = Math.round(3_600_000.0 * (rem / hourUse));
                return t.plusMillis(Math.min(add, msLeftInHour));
            }
            rem -= useFull;
            t = t.plusMillis(msLeftInHour);
        }
        return t;
    }

    private static long millisLeftInHour(ZonedDateTime z) {
        return 3_600_000L
                - z.getMinute() * 60_000L
                - z.getSecond() * 1_000L
                - z.getNano() / 1_000_000L;
    }

    private static BigDecimal median(List<BigDecimal> rates) {
        if (rates.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(rates);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.TWO, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
