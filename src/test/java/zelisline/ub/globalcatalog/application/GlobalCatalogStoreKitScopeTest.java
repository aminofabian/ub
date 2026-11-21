package zelisline.ub.globalcatalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.globalcatalog.application.GlobalCatalogService.StoreKitScope;

/** Which catalogue content a shop is offered, by its formats. */
class GlobalCatalogStoreKitScopeTest {

    @Test
    void aChemistIsOfferedOnlyPharmacyContent() {
        StoreKitScope scope = StoreKitScope.forShopTypes(List.of("pharmacy"));
        assertEquals(List.of("pharmacy"), scope.kits());
        assertFalse(scope.includeUntagged());
        assertTrue(scope.showsPack("pharmacy"));
        assertFalse(scope.showsPack("mini-mart"));
        assertFalse(scope.showsPack("mixed-shop"));
        assertFalse(scope.showsPack("cosmetics"));
        assertFalse(scope.showsPack(null));
        assertFalse(scope.showsPack("  "));
    }

    @Test
    void aMiniMartIsNeverOfferedAnotherVerticalsPack() {
        StoreKitScope scope = StoreKitScope.forShopTypes(List.of("mini-mart"));
        assertEquals(List.of("mini-mart", "grocery"), scope.kits());
        assertTrue(scope.includeUntagged());
        assertTrue(scope.showsPack("mini-mart"));
        assertTrue(scope.showsPack("mixed-shop"));
        assertTrue(scope.showsPack(null));
        assertFalse(scope.showsPack("pharmacy"));
        assertFalse(scope.showsPack("wines-spirits"));
    }

    @Test
    void aChemistThatIsAlsoAMiniMartSeesBothItsOwn() {
        StoreKitScope scope = StoreKitScope.forShopTypes(List.of("pharmacy", "mini-mart"));
        assertTrue(scope.showsPack("pharmacy"));
        assertTrue(scope.showsPack("mini-mart"));
        assertTrue(scope.showsPack("mixed-shop"));
        assertFalse(scope.showsPack("cosmetics"));
        assertFalse(scope.includeUntagged());
    }

    @Test
    void aShopOfUnknownFormatIsTreatedAsAGeneralShop() {
        StoreKitScope scope = StoreKitScope.forShopTypes(List.of());
        assertEquals(List.of("grocery"), scope.kits());
        assertTrue(scope.includeUntagged());
        assertFalse(scope.showsPack("pharmacy"));
        assertTrue(scope.showsPack("mini-mart"));
    }
}
