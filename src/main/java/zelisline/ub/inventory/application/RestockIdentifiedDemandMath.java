package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import zelisline.ub.sales.application.CustomerItemRhythmMath;

/**
 * Identified demand for one SKU at a branch: regulars (linked buyers with a
 * rhythm), how many are due this week, and whether capture is high enough to
 * nudge velocity qty. Anonymous velocity stays the engine.
 */
public final class RestockIdentifiedDemandMath {

    public static final double CAPTURE_NUDGE_THRESHOLD = 0.25d;
    public static final int REGULAR_MIN_DAYS = CustomerItemRhythmMath.MIN_PURCHASES;
    public static final int DUE_WINDOW_DAYS = 7;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final int QTY_SCALE = 4;

    private RestockIdentifiedDemandMath() {
    }

    /** One purchase day for one customer on one SKU. */
    public record PurchaseDay(String customerId, LocalDate day, BigDecimal qty) {
    }

    public record Demand(
            int regularCount,
            Integer typicalGapDays,
            int dueThisWeek,
            BigDecimal identifiedDueQty,
            BigDecimal capturePct,
            String explain
    ) {
        public boolean shouldNudge(BigDecimal velocityQty) {
            if (capturePct == null
                    || capturePct.doubleValue() < CAPTURE_NUDGE_THRESHOLD
                    || identifiedDueQty == null
                    || identifiedDueQty.signum() <= 0) {
                return false;
            }
            BigDecimal velocity = velocityQty == null ? ZERO : velocityQty;
            return identifiedDueQty.compareTo(velocity) > 0;
        }
    }

    public static Demand compute(
            List<PurchaseDay> days,
            BigDecimal linkedQtyLast30,
            BigDecimal allQtyLast30,
            LocalDate asOf
    ) {
        if (asOf == null) {
            return empty();
        }
        Map<String, List<LocalDate>> daysByCustomer = new HashMap<>();
        Map<String, BigDecimal> qtyByCustomer = new HashMap<>();
        if (days != null) {
            for (PurchaseDay row : days) {
                if (row == null || row.customerId() == null || row.customerId().isBlank() || row.day() == null) {
                    continue;
                }
                daysByCustomer.computeIfAbsent(row.customerId(), k -> new ArrayList<>()).add(row.day());
                BigDecimal q = row.qty() == null ? ZERO : row.qty();
                qtyByCustomer.merge(row.customerId(), q, BigDecimal::add);
            }
        }

        List<Integer> gaps = new ArrayList<>();
        int dueThisWeek = 0;
        BigDecimal dueQty = ZERO;
        int regulars = 0;
        for (Map.Entry<String, List<LocalDate>> e : daysByCustomer.entrySet()) {
            List<LocalDate> purchaseDays = e.getValue();
            if (purchaseDays.size() < REGULAR_MIN_DAYS) {
                continue;
            }
            Integer median = CustomerItemRhythmMath.medianGapDays(purchaseDays);
            if (median == null || median <= 0) {
                continue;
            }
            regulars++;
            gaps.add(median);
            LocalDate last = purchaseDays.stream().filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            if (last == null) {
                continue;
            }
            int daysSince = (int) ChronoUnit.DAYS.between(last, asOf);
            if (!dueThisWeek(daysSince, median)) {
                continue;
            }
            dueThisWeek++;
            int dayCount = (int) purchaseDays.stream().filter(Objects::nonNull).distinct().count();
            BigDecimal typical = typicalQty(qtyByCustomer.get(e.getKey()), dayCount);
            dueQty = dueQty.add(typical);
        }

        Integer typicalGap = medianInt(gaps);
        BigDecimal capture = capturePct(linkedQtyLast30, allQtyLast30);
        String explain = explain(regulars, typicalGap, dueThisWeek);
        return new Demand(
                regulars,
                typicalGap,
                dueThisWeek,
                dueQty.setScale(QTY_SCALE, RoundingMode.HALF_UP),
                capture,
                explain);
    }

    static boolean dueThisWeek(int daysSinceLast, int medianGapDays) {
        if (medianGapDays <= 0 || daysSinceLast < 0) {
            return false;
        }
        // Expected next buy is within the coming week, or already past.
        return daysSinceLast >= medianGapDays - DUE_WINDOW_DAYS;
    }

    static BigDecimal capturePct(BigDecimal linkedQty, BigDecimal allQty) {
        if (allQty == null || allQty.signum() <= 0) {
            return ZERO;
        }
        BigDecimal linked = linkedQty == null ? ZERO : linkedQty;
        return linked.divide(allQty, QTY_SCALE, RoundingMode.HALF_UP).max(ZERO);
    }

    static Demand empty() {
        return new Demand(0, null, 0, ZERO, ZERO, null);
    }

    private static BigDecimal typicalQty(BigDecimal totalQty, int dayCount) {
        if (dayCount <= 0) {
            return BigDecimal.ONE.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = totalQty == null || totalQty.signum() <= 0
                ? BigDecimal.valueOf(dayCount)
                : totalQty;
        BigDecimal avg = total.divide(BigDecimal.valueOf(dayCount), QTY_SCALE, RoundingMode.HALF_UP);
        return avg.signum() > 0 ? avg : BigDecimal.ONE.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static Integer medianInt(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (int) Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0d);
    }

    static String explain(int regularCount, Integer typicalGapDays, int dueThisWeek) {
        if (regularCount <= 0) {
            return null;
        }
        String people = regularCount == 1 ? "1 regular" : regularCount + " regulars";
        String verb = regularCount == 1 ? "buys" : "buy";
        String gap = typicalGapDays == null || typicalGapDays <= 0
                ? ""
                : " usually " + verb + " this every ~" + typicalGapDays + " days";
        if (gap.isEmpty()) {
            gap = " " + verb + " this regularly";
        }
        String due = dueThisWeek == 1
                ? "1 is due this week"
                : dueThisWeek + " are due this week";
        return people + gap + "; " + due;
    }
}
