package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.credits.api.dto.TabPurchaseLineResponse;
import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;

class PurchaseHistorySearchTest {

    @Test
    void matchesSkuIgnoringHyphensAndCase() {
        TabPurchaseRowResponse row = row("Molped 14s", "F11301", null);
        assertThat(PurchaseHistorySearch.matches(row, "f11301")).isTrue();
        assertThat(PurchaseHistorySearch.matches(row, "F-11301")).isTrue();
        assertThat(PurchaseHistorySearch.matches(row, "xyzzy")).isFalse();
    }

    @Test
    void matchesBarcodeAndItemName() {
        TabPurchaseRowResponse row = row("Supa Loaf Small", "SKU-9", "6161101234567");
        assertThat(PurchaseHistorySearch.matches(row, "6161101234567")).isTrue();
        assertThat(PurchaseHistorySearch.matches(row, "supa loaf")).isTrue();
    }

    @Test
    void matchesReceiptNumber() {
        TabPurchaseRowResponse row = row("Item", "SKU", null);
        assertThat(PurchaseHistorySearch.matches(row, "42")).isTrue();
    }

    @Test
    void blankQueryMatchesEverything() {
        assertThat(PurchaseHistorySearch.matches(row("Item", "SKU", null), "  ")).isTrue();
        assertThat(PurchaseHistorySearch.matches(row("Item", "SKU", null), null)).isTrue();
    }

    private static TabPurchaseRowResponse row(String name, String sku, String barcode) {
        return new TabPurchaseRowResponse(
                "sale-1",
                42L,
                Instant.parse("2026-09-01T10:00:00Z"),
                "completed",
                BigDecimal.ZERO,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                List.of(new TabPurchaseLineResponse(
                        name,
                        sku,
                        barcode,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN)));
    }
}
