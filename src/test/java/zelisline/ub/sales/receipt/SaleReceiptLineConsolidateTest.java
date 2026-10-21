package zelisline.ub.sales.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SaleLineKinds;

class SaleReceiptLineConsolidateTest {

    @Test
    void buildReceiptLines_mergesBatchSplitsForSameLineIndex() {
        SaleItem a = allocation("item-1", 0, "3000", "3000.00");
        SaleItem b = allocation("item-1", 0, "4000", "4000.00");
        SaleItem c = allocation("item-1", 0, "3000", "3000.00");
        SaleItem other = allocation("item-2", 1, "2", "20.00");

        Item sku = new Item();
        sku.setId("item-1");
        sku.setName("Cooking Oil 1L");
        Item sku2 = new Item();
        sku2.setId("item-2");
        sku2.setName("Soda");

        List<ReceiptLineRow> lines = SaleReceiptService.buildReceiptLines(
                List.of(a, b, c, other),
                Map.of("item-1", sku, "item-2", sku2),
                Map.of()
        );

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).description()).isEqualTo("Cooking Oil 1L");
        assertThat(lines.get(0).quantity()).isEqualTo("10000");
        assertThat(lines.get(0).lineTotal()).isEqualTo("10000.00");
        assertThat(lines.get(1).description()).isEqualTo("Soda");
        assertThat(lines.get(1).quantity()).isEqualTo("2");
    }

    @Test
    void buildReceiptLines_prefersTheDisplayNameFrozenOnTheSaleLine() {
        SaleItem line = allocation("item-1", 0, "1", "35.00");
        line.setDisplayName("CACTUS CT-PEARL/WHITE 10MM");

        Item renamed = new Item();
        renamed.setId("item-1");
        renamed.setName("Renamed after the sale");

        List<ReceiptLineRow> lines = SaleReceiptService.buildReceiptLines(
                List.of(line),
                Map.of("item-1", renamed),
                Map.of()
        );

        assertThat(lines.get(0).description()).isEqualTo("CACTUS CT-PEARL/WHITE 10MM");
    }

    @Test
    void buildReceiptLines_fallsBackToTheLiveCatalogForLegacyRows() {
        SaleItem line = allocation("item-1", 0, "1", "30.00");

        Item variant = new Item();
        variant.setId("item-1");
        variant.setName("CT-PEARL/WHITE 6MM");
        variant.setVariantName("CT-PEARL/WHITE 6MM");
        variant.setVariantOfItemId("parent-1");

        List<ReceiptLineRow> lines = SaleReceiptService.buildReceiptLines(
                List.of(line),
                Map.of("item-1", variant),
                Map.of("parent-1", "CACTUS")
        );

        assertThat(lines.get(0).description()).isEqualTo("CACTUS CT-PEARL/WHITE 6MM");
    }

    private static SaleItem allocation(String itemId, int lineIndex, String qty, String total) {
        SaleItem si = new SaleItem();
        si.setItemId(itemId);
        si.setLineIndex(lineIndex);
        si.setLineKind(SaleLineKinds.ITEM);
        si.setQuantity(new BigDecimal(qty));
        si.setUnitPrice(new BigDecimal("1.00"));
        si.setLineTotal(new BigDecimal(total));
        return si;
    }
}
