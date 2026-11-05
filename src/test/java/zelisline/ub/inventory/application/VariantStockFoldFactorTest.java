package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;

/**
 * Fold conversion rules for {@code /inventory/variant-stock-fold}
 * (PACKAGE_FLOW_GAPS G1/G4/G7): how many base units each row unit of an
 * option becomes when its stranded stock moves into the base pool.
 */
class VariantStockFoldFactorTest {

    @Test
    void flavorOption_convertsOneToOne() {
        Item v = new Item();
        assertThat(InventoryLedgerService.variantFoldFactor(v))
                .isEqualByComparingTo("1");
    }

    @Test
    void packRow_convertsByPackagingUnitQty() {
        Item v = new Item();
        v.setPackageVariant(true);
        v.setPackagingUnitQty(new BigDecimal("24"));
        assertThat(InventoryLedgerService.variantFoldFactor(v))
                .isEqualByComparingTo("24");
    }

    @Test
    void sharedUnitQtyNotOne_convertsEvenWithoutPackageFlag() {
        // packagingUnitQty set and != 1 is a shared selling unit by resolver rules.
        Item v = new Item();
        v.setPackagingUnitQty(new BigDecimal("30"));
        assertThat(InventoryLedgerService.variantFoldFactor(v))
                .isEqualByComparingTo("30");
    }

    @Test
    void singleAtQtyOneWithoutFlag_staysOneToOne() {
        Item v = new Item();
        v.setPackagingUnitQty(BigDecimal.ONE);
        assertThat(InventoryLedgerService.variantFoldFactor(v))
                .isEqualByComparingTo("1");
    }

    @Test
    void packageFlagAtQtyOne_stillOneToOne_byValue() {
        // "Single" marked packageVariant with qty 1: factor 1 keeps value conserved.
        Item v = new Item();
        v.setPackageVariant(true);
        v.setPackagingUnitQty(BigDecimal.ONE);
        assertThat(InventoryLedgerService.variantFoldFactor(v))
                .isEqualByComparingTo("1");
    }
}
