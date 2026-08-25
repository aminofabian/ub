package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Route resolution is what decides which page facts the model sees. These
 * tests guard the two ordering traps (stock-take before inventory, business
 * settings before hub) and the strict surface matcher (no "ap" substring
 * matching on "app.general").
 */
class RouteGuideCatalogTest {

    private final RouteGuideCatalog catalog = new RouteGuideCatalog();

    private String surfaceFor(String route, String surface) {
        return catalog.resolve(route, surface).surface();
    }

    @Test
    void purchasingPagesResolveToTheirOwnSurfaces() {
        assertThat(surfaceFor("/purchasing/ap-aging", "app.general")).isEqualTo("purchasing.ap");
        assertThat(surfaceFor("/purchasing/record-payment", "app.general")).isEqualTo("purchasing.pay");
        assertThat(surfaceFor("/purchasing/intelligence", "app.general")).isEqualTo("purchasing.intel");
        assertThat(surfaceFor("/supplies", "app.general")).isEqualTo("purchasing.supplies");
    }

    @Test
    void stockTakeMustWinOverGenericInventory() {
        assertThat(surfaceFor("/inventory/stock-take", "inventory.stocktake")).isEqualTo("inventory.stocktake");
        assertThat(surfaceFor("/inventory/stock-take/daily-audit", "inventory.stocktake"))
                .isEqualTo("inventory.stocktake");
        assertThat(surfaceFor("/inventory/stock", "inventory.stock")).isEqualTo("inventory.stock");
        assertThat(surfaceFor("/inventory/restock", "inventory.stock")).isEqualTo("inventory.stock");
    }

    @Test
    void restockDigestMustWinOverGenericInventory() {
        assertThat(surfaceFor("/inventory/restock-digest/abc123", "inventory.restockdigest"))
                .isEqualTo("inventory.restockdigest");
        assertThat(surfaceFor("/inventory/restock-digest/abc123/prep", "inventory.restockdigest"))
                .isEqualTo("inventory.restockdigest");
        assertThat(surfaceFor("/inventory/restock", "inventory.stock")).isEqualTo("inventory.stock");
    }

    @Test
    void businessSettingsMustWinOverBusinessHub() {
        assertThat(surfaceFor("/business/settings", "business.settings")).isEqualTo("business.settings");
        assertThat(surfaceFor("/business", "business.hub")).isEqualTo("business.hub");
    }

    @Test
    void appGeneralNeverMatchesTheApToken() {
        assertThat(surfaceFor("/some/unknown/page", "app.general")).isEqualTo("app.general");
        assertThat(surfaceFor("/", "app.general")).isEqualTo("app.general");
    }

    @Test
    void newSurfacesResolveToTheirOwnEntries() {
        assertThat(surfaceFor("/order", "ordering")).isEqualTo("ordering");
        assertThat(surfaceFor("/item-types", "departments")).isEqualTo("departments");
        assertThat(surfaceFor("/categories", "categories")).isEqualTo("categories");
        assertThat(surfaceFor("/customers", "customers")).isEqualTo("customers");
        assertThat(surfaceFor("/credits", "credits")).isEqualTo("credits");
        assertThat(surfaceFor("/shifts", "shifts")).isEqualTo("shifts");
        assertThat(surfaceFor("/messages", "messages")).isEqualTo("messages");
        assertThat(surfaceFor("/storefront/web-orders", "storefront")).isEqualTo("storefront");
        assertThat(surfaceFor("/sales", "sales")).isEqualTo("sales");
        assertThat(surfaceFor("/discounts", "discounts")).isEqualTo("discounts");
        assertThat(surfaceFor("/payments/day", "payments.day")).isEqualTo("payments.day");
        assertThat(surfaceFor("/payments/settings", "payments.settings")).isEqualTo("payments.settings");
        assertThat(surfaceFor("/payroll", "payroll")).isEqualTo("payroll");
        assertThat(surfaceFor("/users", "users")).isEqualTo("users");
        assertThat(surfaceFor("/supplier-portal/claim", "supplier-portal")).isEqualTo("supplier-portal");
    }

    @Test
    void dottedSurfaceTokensStillMatchTheirEntries() {
        assertThat(surfaceFor("/products", "products.catalog")).isEqualTo("products.catalog");
        assertThat(surfaceFor("/suppliers", "suppliers.ap")).isEqualTo("suppliers.ap");
        assertThat(surfaceFor("/analytics", "analytics")).isEqualTo("analytics");
        assertThat(surfaceFor("/marketplace", "marketplace")).isEqualTo("marketplace");
    }
}
