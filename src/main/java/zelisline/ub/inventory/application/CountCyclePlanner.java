package zelisline.ub.inventory.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.StockCountCycleItem;

/** Pure scheduling rules for full-count cycles. */
public final class CountCyclePlanner {

    private static final int UNCATEGORISED_POSITION = Integer.MAX_VALUE;

    private CountCyclePlanner() {
    }

    public record CatalogEntry(String itemId, String categoryId, String name) {}

    /** Category by category (store order), then A–Z — the order a counter walks the shelves. */
    public static List<String> shelfWalkOrder(
            List<CatalogEntry> entries,
            Map<String, Integer> categoryPositions
    ) {
        Comparator<CatalogEntry> byShelf = Comparator
                .comparingInt((CatalogEntry e) ->
                        categoryPositions.getOrDefault(e.categoryId(), UNCATEGORISED_POSITION))
                .thenComparing(e -> normalisedName(e.name()))
                .thenComparing(CatalogEntry::itemId);
        return entries.stream().sorted(byShelf).map(CatalogEntry::itemId).toList();
    }

    /** The next {@code batchSize} products still waiting to be counted, in shelf order. */
    public static List<StockCountCycleItem> nextBatch(
            List<StockCountCycleItem> items,
            int batchSize
    ) {
        return items.stream()
                .filter(StockCountCycleItem::isInPool)
                .sorted(Comparator.comparingInt(StockCountCycleItem::getSortOrder))
                .limit(Math.max(0, batchSize))
                .toList();
    }

    public static int clampBatchSize(Integer requested) {
        if (requested == null) {
            return InventoryConstants.COUNT_CYCLE_DEFAULT_BATCH_SIZE;
        }
        return Math.min(
                InventoryConstants.COUNT_CYCLE_MAX_BATCH_SIZE,
                Math.max(InventoryConstants.COUNT_CYCLE_MIN_BATCH_SIZE, requested)
        );
    }

    public static int batchesRemaining(int remainingItems, int batchSize) {
        if (remainingItems <= 0 || batchSize <= 0) {
            return 0;
        }
        return (remainingItems + batchSize - 1) / batchSize;
    }

    /**
     * Last counting day at one batch per day. When today's batch is already
     * done, the next batch starts tomorrow.
     */
    public static LocalDate projectedFinish(
            LocalDate today,
            int remainingItems,
            int batchSize,
            boolean countedToday
    ) {
        int days = batchesRemaining(remainingItems, batchSize);
        if (days == 0) {
            return today;
        }
        LocalDate firstDay = countedToday ? today.plusDays(1) : today;
        return firstDay.plusDays(days - 1L);
    }

    private static String normalisedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
