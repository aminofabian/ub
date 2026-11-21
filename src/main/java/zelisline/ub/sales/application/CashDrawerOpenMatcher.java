package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairs each drawer opening with one cash payment or one drawout.
 * A payment or drawout explains at most one opening. Openings are
 * taken oldest first, so a later reprint of the same sale stays unmatched.
 */
public final class CashDrawerOpenMatcher {

    public static final Duration WINDOW = Duration.ofMinutes(2);
    public static final String KIND_CASH = "CASH";
    public static final String KIND_DRAWOUT = "DRAWOUT";

    public record Opening(String id, Instant openedAt, String saleId) {}

    public record CashSale(String saleId, String label, Instant soldAt, BigDecimal cashAmount) {}

    public record Drawout(String id, Instant at, BigDecimal amount, String label) {}

    public record Hit(String kind, String refId, String label, BigDecimal amount, Instant at) {}

    private CashDrawerOpenMatcher() {}

    public static Map<String, Hit> match(
            List<Opening> openings,
            List<CashSale> sales,
            List<Drawout> drawouts) {
        List<Opening> ordered = new ArrayList<>(openings);
        ordered.sort(Comparator
                .comparing(Opening::openedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Opening::id, Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, CashSale> cashBySale = indexCash(sales);
        List<Drawout> usableDrawouts = usableDrawouts(drawouts);
        Set<String> usedSales = new HashSet<>();
        Set<String> usedDrawouts = new HashSet<>();
        Map<String, Hit> hits = new LinkedHashMap<>();
        for (Opening opening : ordered) {
            if (opening.id() == null || opening.openedAt() == null) {
                continue;
            }
            Hit hit = hitFor(opening, cashBySale, usedSales, usableDrawouts, usedDrawouts);
            if (hit != null) {
                hits.put(opening.id(), hit);
            }
        }
        return hits;
    }

    private static Hit hitFor(
            Opening opening,
            Map<String, CashSale> cashBySale,
            Set<String> usedSales,
            List<Drawout> drawouts,
            Set<String> usedDrawouts) {
        if (pinnedToCash(opening, cashBySale)) {
            return claimDirect(opening, cashBySale, usedSales);
        }
        Hit cash = claimNearestCash(opening, cashBySale, usedSales);
        if (cash != null) {
            return cash;
        }
        return claimNearestDrawout(opening, drawouts, usedDrawouts);
    }

    private static boolean pinnedToCash(Opening opening, Map<String, CashSale> cashBySale) {
        return opening.saleId() != null && cashBySale.containsKey(opening.saleId());
    }

    private static Hit claimDirect(
            Opening opening,
            Map<String, CashSale> cashBySale,
            Set<String> used) {
        CashSale sale = cashBySale.get(opening.saleId());
        if (sale == null || !used.add(sale.saleId())) {
            return null;
        }
        return cashHit(sale);
    }

    private static Hit claimNearestCash(
            Opening opening,
            Map<String, CashSale> cashBySale,
            Set<String> used) {
        CashSale nearest = null;
        long bestGap = Long.MAX_VALUE;
        for (CashSale sale : cashBySale.values()) {
            if (used.contains(sale.saleId()) || sale.soldAt() == null) {
                continue;
            }
            long gap = gapMillis(sale.soldAt(), opening.openedAt());
            if (gap > WINDOW.toMillis()) {
                continue;
            }
            if (nearest == null || closer(gap, sale.soldAt(), sale.saleId(), bestGap, nearest)) {
                nearest = sale;
                bestGap = gap;
            }
        }
        if (nearest == null) {
            return null;
        }
        used.add(nearest.saleId());
        return cashHit(nearest);
    }

    private static Hit claimNearestDrawout(
            Opening opening,
            List<Drawout> drawouts,
            Set<String> used) {
        Drawout nearest = null;
        long bestGap = Long.MAX_VALUE;
        for (Drawout drawout : drawouts) {
            if (used.contains(drawout.id())) {
                continue;
            }
            long gap = gapMillis(drawout.at(), opening.openedAt());
            if (gap > WINDOW.toMillis()) {
                continue;
            }
            if (nearest == null || closer(gap, drawout.at(), drawout.id(), bestGap, nearest.at(), nearest.id())) {
                nearest = drawout;
                bestGap = gap;
            }
        }
        if (nearest == null) {
            return null;
        }
        used.add(nearest.id());
        return new Hit(KIND_DRAWOUT, nearest.id(), nearest.label(), nearest.amount(), nearest.at());
    }

    private static boolean closer(long gap, Instant at, String id, long bestGap, CashSale best) {
        return closer(gap, at, id, bestGap, best.soldAt(), best.saleId());
    }

    private static boolean closer(
            long gap,
            Instant at,
            String id,
            long bestGap,
            Instant bestAt,
            String bestId) {
        if (gap != bestGap) {
            return gap < bestGap;
        }
        int byTime = at.compareTo(bestAt);
        if (byTime != 0) {
            return byTime < 0;
        }
        return id.compareTo(bestId) < 0;
    }

    private static long gapMillis(Instant left, Instant right) {
        return Math.abs(Duration.between(left, right).toMillis());
    }

    private static Hit cashHit(CashSale sale) {
        return new Hit(KIND_CASH, sale.saleId(), sale.label(), sale.cashAmount(), sale.soldAt());
    }

    private static Map<String, CashSale> indexCash(List<CashSale> sales) {
        Map<String, CashSale> bySale = new LinkedHashMap<>();
        for (CashSale sale : sales) {
            if (sale.saleId() == null || sale.cashAmount() == null || sale.cashAmount().signum() <= 0) {
                continue;
            }
            bySale.putIfAbsent(sale.saleId(), sale);
        }
        return bySale;
    }

    private static List<Drawout> usableDrawouts(List<Drawout> drawouts) {
        List<Drawout> usable = new ArrayList<>();
        for (Drawout drawout : drawouts) {
            if (drawout.id() == null || drawout.at() == null || drawout.amount() == null) {
                continue;
            }
            if (drawout.amount().signum() <= 0) {
                continue;
            }
            usable.add(drawout);
        }
        return usable;
    }
}
