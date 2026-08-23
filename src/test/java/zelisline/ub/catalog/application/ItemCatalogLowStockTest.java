package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;

/**
 * Phase-4 alignment: the catalog low-stock flag must use the same definition as the
 * restock engine (on-hand ≤ reorder level, fallback min level), not a flat number.
 */
class ItemCatalogLowStockTest {

    private static Item item(BigDecimal reorder, BigDecimal min) {
        Item i = new Item();
        i.setReorderLevel(reorder);
        i.setMinStockLevel(min);
        return i;
    }

    @Test
    void reorderLevelWinsOverMinLevel() {
        Item i = item(new BigDecimal("5"), new BigDecimal("9"));
        assertThat(ItemCatalogService.lowStockThreshold(i)).isEqualByComparingTo("5");
    }

    @Test
    void minLevelIsFallbackWhenNoReorderLevel() {
        Item i = item(null, new BigDecimal("7"));
        assertThat(ItemCatalogService.lowStockThreshold(i)).isEqualByComparingTo("7");
    }

    @Test
    void legacyFlatLevelWhenNoThresholdAtAll() {
        Item i = item(null, null);
        assertThat(ItemCatalogService.lowStockThreshold(i)).isEqualByComparingTo("10");
    }

    @Test
    void onHandAtOrBelowReorderLevel_isLow() {
        Item i = item(new BigDecimal("5"), null);
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("5"), i)).isTrue();
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("4.5"), i)).isTrue();
    }

    @Test
    void onHandAboveReorderLevel_isNotLow() {
        // Discriminating case vs the legacy flat-10 rule: 6 is below 10 but above reorder 3.
        Item i = item(new BigDecimal("3"), null);
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("6"), i)).isFalse();
    }

    @Test
    void onHandBelowMinLevel_isLow() {
        Item i = item(null, new BigDecimal("7"));
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("6"), i)).isTrue();
    }

    @Test
    void thresholdlessItem_keepsLegacyLevel() {
        Item i = item(null, null);
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("4"), i)).isTrue();
        assertThat(ItemCatalogService.isCatalogLowStock(new BigDecimal("11"), i)).isFalse();
    }
}
