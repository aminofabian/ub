package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;

/**
 * The master-data pull must not overwrite the till's stock for an item it
 * already holds. The cloud ingest does not deduct stock for till sales, so
 * taking the snapshot value on every sync would raise the count back up after
 * every sale (the feedback loop in docs/scopes/DESKTOP_APP_AUDIT_SCOPE.md
 * §4.5). Only a brand-new item — one the till has never held — seeds its
 * count from the cloud.
 */
class DesktopItemStockPolicyTest {

    private static MasterDataSnapshot.ItemData cloudItem(BigDecimal currentStock) {
        return new MasterDataSnapshot.ItemData(
            "item-1", "SKU1", null, null, "Sugar", null, "cat-1", "each",
            true, currentStock,
            null, null, null, null, null, null, null,
            true, "type-1");
    }

    private static Map<String, String> itemTypes() {
        return Map.of("type-1", "type-1");
    }

    @Test
    void existingItemKeepsItsLocalStock() {
        Item item = new Item();
        item.setCurrentStock(new BigDecimal("5"));

        DesktopSyncPullService.applyItem(
            item, cloudItem(new BigDecimal("10")), "type-1", itemTypes(), false);

        assertEquals(0, item.getCurrentStock().compareTo(new BigDecimal("5")),
            "an existing item must keep the till's count, not the snapshot's");
    }

    @Test
    void newItemSeedsStockFromTheCloud() {
        Item item = new Item();
        item.setCurrentStock(BigDecimal.ZERO);

        DesktopSyncPullService.applyItem(
            item, cloudItem(new BigDecimal("10")), "type-1", itemTypes(), true);

        assertEquals(0, item.getCurrentStock().compareTo(new BigDecimal("10")),
            "a brand-new item takes the cloud count as its baseline");
    }
}
