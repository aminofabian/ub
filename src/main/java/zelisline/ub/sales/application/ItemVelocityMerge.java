package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zelisline.ub.sales.api.dto.ItemVelocityRow;

/**
 * Merges MV past-day buckets with live OLTP "today" totals so rolling windows
 * stay honest when {@code mv_sales_daily} lags the current business day.
 */
public final class ItemVelocityMerge {

    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private ItemVelocityMerge() {
    }

    public record PastBuckets(
            String itemId,
            String itemName,
            String sku,
            BigDecimal currentStock,
            BigDecimal yesterdayQty,
            BigDecimal yesterdayRevenue,
            BigDecimal last3PastQty,
            BigDecimal last3PastRevenue,
            BigDecimal last7PastQty,
            BigDecimal last7PastRevenue,
            BigDecimal last30PastQty,
            BigDecimal last30PastRevenue
    ) {
    }

    public record TodayTotals(BigDecimal qty, BigDecimal revenue) {
        public static TodayTotals zero() {
            return new TodayTotals(QTY_ZERO, MONEY_ZERO);
        }
    }

    public static List<ItemVelocityRow> merge(
            List<PastBuckets> pastRows,
            Map<String, TodayTotals> todayByItem,
            Map<String, ItemMeta> todayOnlyMeta,
            int limit
    ) {
        Map<String, ItemVelocityRow> byId = new HashMap<>();

        for (PastBuckets past : pastRows) {
            TodayTotals today = todayByItem.getOrDefault(past.itemId(), TodayTotals.zero());
            byId.put(past.itemId(), toRow(past, today));
        }

        for (Map.Entry<String, TodayTotals> e : todayByItem.entrySet()) {
            if (byId.containsKey(e.getKey())) {
                continue;
            }
            ItemMeta meta = todayOnlyMeta.get(e.getKey());
            if (meta == null) {
                continue;
            }
            PastBuckets empty = new PastBuckets(
                    meta.itemId(),
                    meta.itemName(),
                    meta.sku(),
                    meta.currentStock(),
                    QTY_ZERO,
                    MONEY_ZERO,
                    QTY_ZERO,
                    MONEY_ZERO,
                    QTY_ZERO,
                    MONEY_ZERO,
                    QTY_ZERO,
                    MONEY_ZERO
            );
            byId.put(e.getKey(), toRow(empty, e.getValue()));
        }

        List<ItemVelocityRow> rows = new ArrayList<>(byId.values());
        rows.sort(Comparator
                .comparing(ItemVelocityRow::last30Qty)
                .reversed()
                .thenComparing(ItemVelocityRow::itemName, Comparator.nullsLast(String::compareToIgnoreCase)));
        if (rows.size() > limit) {
            return List.copyOf(rows.subList(0, limit));
        }
        return List.copyOf(rows);
    }

    private static ItemVelocityRow toRow(PastBuckets past, TodayTotals today) {
        BigDecimal todayQty = qty(today.qty());
        BigDecimal todayRev = money(today.revenue());
        return new ItemVelocityRow(
                past.itemId(),
                past.itemName(),
                past.sku(),
                qty(past.currentStock()),
                todayQty,
                todayRev,
                qty(past.yesterdayQty()),
                money(past.yesterdayRevenue()),
                qty(past.last3PastQty()).add(todayQty),
                money(past.last3PastRevenue()).add(todayRev),
                qty(past.last7PastQty()).add(todayQty),
                money(past.last7PastRevenue()).add(todayRev),
                qty(past.last30PastQty()).add(todayQty),
                money(past.last30PastRevenue()).add(todayRev)
        );
    }

    public record ItemMeta(String itemId, String itemName, String sku, BigDecimal currentStock) {
    }

    private static BigDecimal qty(BigDecimal v) {
        return v == null ? QTY_ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? MONEY_ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
