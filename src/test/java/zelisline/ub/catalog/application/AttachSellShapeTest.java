package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.api.dto.AttachVariantLineRequest;
import zelisline.ub.catalog.domain.Item;

/**
 * Regroup pack-shape rules (PACKAGE_FLOW_GAPS G1): attach lines may mark a row
 * as a shared selling unit of the base product instead of a flavor option.
 */
class AttachSellShapeTest {

    private static Item stockedChild() {
        Item child = new Item();
        child.setStocked(true);
        child.setWeighed(true);
        return child;
    }

    private static AttachVariantLineRequest line(
            Boolean sellsAsPack, BigDecimal unitsPerPack, String packUnitName) {
        return new AttachVariantLineRequest(
                "item-1", "Crate of 24", sellsAsPack, unitsPerPack, packUnitName);
    }

    @Test
    void noPackFields_leavesFlavorOptionUntouched() {
        Item child = stockedChild();
        ItemCatalogService.applyAttachSellShape(child, line(null, null, null));
        assertThat(child.isStocked()).isTrue();
        assertThat(child.getPackagingUnitQty()).isNull();
        assertThat(child.isPackageVariant()).isFalse();
    }

    @Test
    void unitsAboveOne_setsPackShapeAndStopsOwnStock() {
        Item child = stockedChild();
        ItemCatalogService.applyAttachSellShape(
                child, line(null, new BigDecimal("24"), "Crate"));
        assertThat(child.isPackageVariant()).isTrue();
        assertThat(child.getPackagingUnitQty()).isEqualByComparingTo("24");
        assertThat(child.getPackagingUnitName()).isEqualTo("Crate");
        assertThat(child.isStocked()).isFalse();
        // A pack of discrete units is never sold by weight.
        assertThat(child.isWeighed()).isFalse();
    }

    @Test
    void explicitPackAtQtyOne_isSharedUnitNotPack() {
        // "Single" sells one base unit per sale from the shared pool.
        Item child = stockedChild();
        ItemCatalogService.applyAttachSellShape(
                child, line(Boolean.TRUE, BigDecimal.ONE, null));
        assertThat(child.isPackageVariant()).isTrue();
        assertThat(child.getPackagingUnitQty()).isEqualByComparingTo("1");
        // Unit label falls back to the variant name.
        assertThat(child.getPackagingUnitName()).isEqualTo("Crate of 24");
        assertThat(child.isStocked()).isFalse();
    }

    @Test
    void explicitPackWithoutUnits_defaultsToOne() {
        Item child = stockedChild();
        ItemCatalogService.applyAttachSellShape(child, line(Boolean.TRUE, null, null));
        assertThat(child.getPackagingUnitQty()).isEqualByComparingTo("1");
        assertThat(child.isPackageVariant()).isTrue();
    }
}
