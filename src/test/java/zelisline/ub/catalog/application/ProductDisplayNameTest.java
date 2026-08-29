package zelisline.ub.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;

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

    @Test
    void forItemJoinsParentNameToVariantOption() {
        Item item = new Item();
        item.setName("Festive Bread");
        item.setVariantName("400g White");
        item.setSku("FB-400W");
        assertEquals("Festive Bread 400g White", ProductDisplayName.forItem(item));
        assertEquals("400g White", ProductDisplayName.optionLabel(item));
    }

    @Test
    void forItemFallsBackToSizeThenPackThenSku() {
        Item sized = new Item();
        sized.setName("Festive Bread");
        sized.setVariantName("variant");
        sized.setSize("800g");
        assertEquals("Festive Bread 800g", ProductDisplayName.forItem(sized));

        Item packed = new Item();
        packed.setName("Festive Bread");
        packed.setPackageVariant(true);
        packed.setPackagingUnitQty(new java.math.BigDecimal("12"));
        packed.setPackagingUnitName("tray");
        assertEquals("Festive Bread 12 tray", ProductDisplayName.forItem(packed));

        Item skuOnly = new Item();
        skuOnly.setName("Festive Bread");
        skuOnly.setVariantOfItemId("parent-1");
        skuOnly.setSku("FB-WM");
        assertEquals("Festive Bread (FB-WM)", ProductDisplayName.forItem(skuOnly));
        assertEquals("FB-WM", ProductDisplayName.optionLabel(skuOnly));

        Item standalone = new Item();
        standalone.setName("Digest Item");
        standalone.setSku("SKU-DIGEST");
        assertEquals("Digest Item", ProductDisplayName.forItem(standalone));
        assertEquals("", ProductDisplayName.optionLabel(standalone));
    }

    @Test
    void forItemIgnoresInternalSkusAndRepeatedParentName() {
        Item item = new Item();
        item.setName("Festive Bread");
        item.setVariantName("Festive Bread");
        item.setSku("IMP-not-a-label");
        assertEquals("Festive Bread", ProductDisplayName.forItem(item));
        assertEquals("", ProductDisplayName.optionLabel(item));
    }

    @Test
    void forVariantUsesLiveParentNameOverStaleChildCopy() {
        Item variant = new Item();
        variant.setName("Old Bread Name");
        variant.setVariantName("400g White");
        variant.setVariantOfItemId("parent-1");
        assertEquals(
                "Festive Bread 400g White",
                ProductDisplayName.forVariant(variant, "Festive Bread"));
    }
}
