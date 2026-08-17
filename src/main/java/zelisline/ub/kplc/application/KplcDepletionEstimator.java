package zelisline.ub.kplc.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

/**
 * Estimates when the current prepaid slip runs out from how long previous
 * slips lasted. This is not a live meter read — it is the gap between buys.
 */
final class KplcDepletionEstimator {

    private static final Duration MIN_INTERVAL = Duration.ofHours(6);
    private static final int RATE_SCALE = 4;

    private KplcDepletionEstimator() {
    }

    record Estimate(
            Instant estimatedEmptyAt,
            BigDecimal remainingUnits,
            BigDecimal lastPurchaseUnits,
            BigDecimal dailyUseUnits,
            int sampleIntervals,
            boolean alreadyEmpty
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
        List<BigDecimal> rates = new ArrayList<>();
        for (int i = 0; i < dated.size() - 1; i++) {
            Instant start = dated.get(i).purchasedAt();
            Instant end = dated.get(i + 1).purchasedAt();
            Duration gap = Duration.between(start, end);
            if (gap.compareTo(MIN_INTERVAL) < 0) {
                continue;
            }
            double days = gap.toSeconds() / 86_400.0;
            if (days <= 0) {
                continue;
            }
            rates.add(dated.get(i).units().divide(BigDecimal.valueOf(days), RATE_SCALE, RoundingMode.HALF_UP));
        }
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
            double days = span.toSeconds() / 86_400.0;
            rates.add(consumed.divide(BigDecimal.valueOf(days), RATE_SCALE, RoundingMode.HALF_UP));
        }
        BigDecimal daily = median(rates);
        if (daily == null || daily.signum() <= 0) {
            return Optional.empty();
        }
        PublicKplcTokenResponse latest = dated.getLast();
        double elapsedDays = Math.max(0, Duration.between(latest.purchasedAt(), now).toSeconds() / 86_400.0);
        BigDecimal used = daily.multiply(BigDecimal.valueOf(elapsedDays)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal remaining = latest.units().subtract(used);
        boolean alreadyEmpty = remaining.signum() <= 0;
        Instant emptyAt;
        if (alreadyEmpty) {
            remaining = BigDecimal.ZERO;
            double lasted = latest.units().divide(daily, 6, RoundingMode.HALF_UP).doubleValue();
            emptyAt = latest.purchasedAt().plusSeconds(Math.max(0, Math.round(lasted * 86_400)));
        } else {
            double daysLeft = remaining.divide(daily, 6, RoundingMode.HALF_UP).doubleValue();
            emptyAt = now.plusSeconds(Math.max(0, Math.round(daysLeft * 86_400)));
        }
        return Optional.of(new Estimate(
                emptyAt,
                remaining,
                latest.units(),
                daily,
                rates.size(),
                alreadyEmpty));
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
