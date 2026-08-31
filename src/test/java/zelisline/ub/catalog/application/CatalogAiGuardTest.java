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
}
