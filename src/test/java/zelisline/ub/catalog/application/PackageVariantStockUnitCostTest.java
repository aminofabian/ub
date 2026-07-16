package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PackageVariantStockUnitCostTest {

    @Test
    void toStockUnitCost_noExpansion_keepsCatalogCost() {
        var inbound = new PackageVariantStockResolver.StockPickResolution(
                "item-1", new BigDecimal("10.0000"), false);
        BigDecimal cost = PackageVariantStockResolver.toStockUnitCost(
                new BigDecimal("10"), new BigDecimal("25.50"), inbound);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("25.5000"));
    }

    @Test
    void toStockUnitCost_packageExpansion_dividesMoneyAcrossBaseUnits() {
        // 2 trays @ 300/tray → 60 base units @ 10 each
        var inbound = new PackageVariantStockResolver.StockPickResolution(
                "parent-1", new BigDecimal("60.0000"), true);
        BigDecimal cost = PackageVariantStockResolver.toStockUnitCost(
                new BigDecimal("2"), new BigDecimal("300"), inbound);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(PackageVariantStockResolver.catalogExtensionMoney(
                new BigDecimal("2"), new BigDecimal("300")))
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    void toStockUnitCost_rejectsNonPositiveStockQty() {
        var inbound = new PackageVariantStockResolver.StockPickResolution(
                "item-1", BigDecimal.ZERO, false);
        assertThatThrownBy(() -> PackageVariantStockResolver.toStockUnitCost(
                        BigDecimal.ONE, BigDecimal.TEN, inbound))
                .isInstanceOf(ResponseStatusException.class);
    }
}
