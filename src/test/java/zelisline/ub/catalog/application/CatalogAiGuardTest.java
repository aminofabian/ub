package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogAiGuardTest {

    @Test
    void nuvitaCannotGoToBabyCare() {
        assertThat(CatalogAiGuard.allows("Baby Care", "Nuvita", "Nuvita")).isFalse();
        assertThat(CatalogAiGuard.allows("Baby Care", "Nuvita biscuits", null)).isFalse();
    }

    @Test
    void pampersCanGoToBabyCare() {
        assertThat(CatalogAiGuard.allows("Baby Care", "Pampers", null)).isTrue();
        assertThat(CatalogAiGuard.allows("Baby", "Infant formula", null)).isTrue();
    }

    @Test
    void householdDoesNotNeedBabyEvidence() {
        assertThat(CatalogAiGuard.allows("Household", "Omo 1kg", "Omo")).isTrue();
        assertThat(CatalogAiGuard.allows("Biscuits", "Nuvita", null)).isTrue();
    }

    @Test
    void kabrasSugarIsNotCereals() {
        assertThat(CatalogAiGuard.allows("Cereals", "Kabras sugar 2kg", "Kabras")).isFalse();
        assertThat(CatalogAiGuard.allows("Cereals", "Kabras Sugar", null)).isFalse();
        assertThat(CatalogAiGuard.namedShelf("Kabras sugar 2kg", null)).isEqualTo("Sugar");
        assertThat(CatalogAiGuard.isDryGroceryStaple("Kabras sugar", "Kabras")).isTrue();
    }

    @Test
    void dryGroceryStaplesAreNotCereals() {
        assertThat(CatalogAiGuard.allows("Cereals", "sea salt", null)).isFalse();
        assertThat(CatalogAiGuard.allows("Cereals", "Lipton tea bags", "Lipton")).isFalse();
        assertThat(CatalogAiGuard.allows("Cereals", "Pishori rice", null)).isFalse();
        assertThat(CatalogAiGuard.allows("Cereals", "Self-raising flour", null)).isFalse();
        assertThat(CatalogAiGuard.allows("Cereals", "Nuvita biscuits", null)).isFalse();
    }

    @Test
    void namedShelfComesFromTheName() {
        assertThat(CatalogAiGuard.namedShelf("Kabras sugar 2kg", null)).isEqualTo("Sugar");
        assertThat(CatalogAiGuard.namedShelf("sea salt", null)).isEqualTo("Salt");
        assertThat(CatalogAiGuard.namedShelf("Lipton tea bags", "Lipton")).isEqualTo("Tea");
        assertThat(CatalogAiGuard.namedShelf("Pishori rice", null)).isEqualTo("Rice");
        assertThat(CatalogAiGuard.namedShelf("Self-raising flour", null)).isEqualTo("Flour");
        // Maize flour has cereal evidence (maize), so no forced shelf.
        assertThat(CatalogAiGuard.namedShelf("Jogoo maize flour", "Jogoo")).isNull();
        assertThat(CatalogAiGuard.namedShelf("Omo 1kg", "Omo")).isNull();
    }

    @Test
    void weetabixMayBeCereals() {
        assertThat(CatalogAiGuard.allows("Cereals", "Weetabix", null)).isTrue();
        assertThat(CatalogAiGuard.allows("Breakfast", "Corn flakes", null)).isTrue();
        assertThat(CatalogAiGuard.allows("Cereals", "Jogoo maize flour", "Jogoo")).isTrue();
    }
}
