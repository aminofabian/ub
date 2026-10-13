package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.StockCountCycleItem;

class CountCyclePlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void shelfWalkOrder_groupsByCategoryPositionThenName_uncategorisedLast() {
        List<CountCyclePlanner.CatalogEntry> entries = List.of(
                new CountCyclePlanner.CatalogEntry("soap", "household", "Soap"),
                new CountCyclePlanner.CatalogEntry("loose", null, "Loose item"),
                new CountCyclePlanner.CatalogEntry("milk", "dairy", "milk"),
                new CountCyclePlanner.CatalogEntry("butter", "dairy", "Butter"),
                new CountCyclePlanner.CatalogEntry("bleach", "household", "Bleach"));
        Map<String, Integer> positions = Map.of("dairy", 0, "household", 1);

        assertThat(CountCyclePlanner.shelfWalkOrder(entries, positions))
                .containsExactly("butter", "milk", "bleach", "soap", "loose");
    }

    @Test
    void nextBatch_takesFirstUncountedInSortOrder_skippingCountedAndInFlight() {
        StockCountCycleItem counted = item("a", 0);
        counted.setSessionId("s1");
        counted.setCountedAt(Instant.now());
        StockCountCycleItem inFlight = item("b", 1);
        inFlight.setSessionId("s2");
        StockCountCycleItem returned = item("c", 2);
        StockCountCycleItem later = item("d", 3);
        StockCountCycleItem last = item("e", 4);

        List<StockCountCycleItem> batch = CountCyclePlanner.nextBatch(
                List.of(last, later, returned, inFlight, counted), 2);

        assertThat(batch).extracting(StockCountCycleItem::getItemId).containsExactly("c", "d");
    }

    @Test
    void clampBatchSize_defaultsAndBounds() {
        assertThat(CountCyclePlanner.clampBatchSize(null))
                .isEqualTo(InventoryConstants.COUNT_CYCLE_DEFAULT_BATCH_SIZE);
        assertThat(CountCyclePlanner.clampBatchSize(1))
                .isEqualTo(InventoryConstants.COUNT_CYCLE_MIN_BATCH_SIZE);
        assertThat(CountCyclePlanner.clampBatchSize(50_000))
                .isEqualTo(InventoryConstants.COUNT_CYCLE_MAX_BATCH_SIZE);
        assertThat(CountCyclePlanner.clampBatchSize(250)).isEqualTo(250);
    }

    @Test
    void batchesRemaining_roundsUp() {
        assertThat(CountCyclePlanner.batchesRemaining(0, 100)).isZero();
        assertThat(CountCyclePlanner.batchesRemaining(100, 100)).isEqualTo(1);
        assertThat(CountCyclePlanner.batchesRemaining(101, 100)).isEqualTo(2);
        assertThat(CountCyclePlanner.batchesRemaining(1240, 100)).isEqualTo(13);
    }

    @Test
    void projectedFinish_countsTodayUnlessTodaysBatchIsDone() {
        assertThat(CountCyclePlanner.projectedFinish(TODAY, 250, 100, false))
                .isEqualTo(TODAY.plusDays(2));
        assertThat(CountCyclePlanner.projectedFinish(TODAY, 250, 100, true))
                .isEqualTo(TODAY.plusDays(3));
        assertThat(CountCyclePlanner.projectedFinish(TODAY, 0, 100, true)).isEqualTo(TODAY);
    }

    private static StockCountCycleItem item(String itemId, int sortOrder) {
        StockCountCycleItem row = new StockCountCycleItem();
        row.setId("row-" + itemId);
        row.setItemId(itemId);
        row.setSortOrder(sortOrder);
        return row;
    }
}
