package zelisline.ub.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProductDisplayNameTest {

    @Test
    void readsFamilyAndOptionAsOneName() {
        assertEquals(
                "Velvex Scouring Powder Lavender Fragrance 1Kg",
                ProductDisplayName.join("Velvex Products", "Scouring Powder Lavender Fragrance 1Kg"));
        assertEquals("Velvex Scouring Powder 1Kg", ProductDisplayName.join("Velvex Scouring Powder", "1Kg"));
    }

    @Test
    void neverRepeatsAPartTheOtherAlreadyContains() {
        assertEquals(
                "Velvex Tissue White 8Pack",
                ProductDisplayName.join("Velvex Tissue White 8Pack", "Tissue White 8Pack"));
        assertEquals("Velvex Tissue 4Pack", ProductDisplayName.join("Velvex", "Velvex Tissue 4Pack"));
    }

    @Test
    void keepsSingleWordFamilyAndCollapsesWhitespace() {
        assertEquals("Rhino Single 60 Sticks", ProductDisplayName.join("Rhino", "Single 60 Sticks"));
        assertEquals("Products Assorted 1Kg", ProductDisplayName.join("Products", "Assorted 1Kg"));
        assertEquals("Velvex Tissue 4Pack", ProductDisplayName.join("  Velvex   ", " Tissue  4Pack "));
    }

    @Test
    void handlesMissingParts() {
        assertEquals("Velvex Tissue", ProductDisplayName.join("Velvex Tissue", null));
        assertEquals("Tissue 4Pack", ProductDisplayName.join(null, "Tissue 4Pack"));
        assertEquals("", ProductDisplayName.join(null, null));
    }

    @Test
    void bracketsCodesInsteadOfJoiningThemIntoTheName() {
        assertEquals("Velvex Tissue (VLX-9)", ProductDisplayName.withCode("Velvex Tissue", "VLX-9"));
        assertEquals("Velvex Tissue", ProductDisplayName.withCode("Velvex Tissue", " "));
    }
}
