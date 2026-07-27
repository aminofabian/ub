package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketplaceSupplierNamingTest {

    @Test
    void detectsPhonePlaceholderNames() {
        assertThat(MarketplaceSupplierNaming.isPlaceholderName("Supplier 2874")).isTrue();
        assertThat(MarketplaceSupplierNaming.isPlaceholderName("supplier 0042")).isTrue();
        assertThat(MarketplaceSupplierNaming.isPlaceholderName(null)).isTrue();
        assertThat(MarketplaceSupplierNaming.isPlaceholderName("  ")).isTrue();
        assertThat(MarketplaceSupplierNaming.isPlaceholderName("Grocery (Githurai)")).isFalse();
        assertThat(MarketplaceSupplierNaming.isPlaceholderName("Supplier Mart")).isFalse();
    }

    @Test
    void prefersRealDisplayName() {
        assertThat(MarketplaceSupplierNaming.preferDisplayName(
                "Grocery (Githurai)", "Supplier 2874"))
                .isEqualTo("Grocery (Githurai)");
        assertThat(MarketplaceSupplierNaming.preferDisplayName(
                "Supplier 2874", "Grocery (Githurai)"))
                .isEqualTo("Grocery (Githurai)");
        assertThat(MarketplaceSupplierNaming.preferDisplayName(null, "Supplier 2874"))
                .isEqualTo("Supplier 2874");
    }

    @Test
    void placeholderFromPhoneUsesLastFour() {
        assertThat(MarketplaceSupplierNaming.placeholderFromPhone("254712345874"))
                .isEqualTo("Supplier 5874");
        assertThat(MarketplaceSupplierNaming.placeholderFromPhone(null))
                .isEqualTo("Supplier");
    }
}
