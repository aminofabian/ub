package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.sales.api.dto.ItemVelocityRow;
import zelisline.ub.sales.application.ItemVelocityMerge.ItemMeta;
import zelisline.ub.sales.application.ItemVelocityMerge.PastBuckets;
import zelisline.ub.sales.application.ItemVelocityMerge.TodayTotals;

class ItemVelocityMergeTest {

    @Test
    void addsTodayOltpIntoRollingWindows() {
        PastBuckets eggs = new PastBuckets(
                "item-eggs",
                "Eggs",
                "EGG-1",
                new BigDecimal("48.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("500.00"),
                new BigDecimal("20.0000"),
                new BigDecimal("1000.00"),
                new BigDecimal("40.0000"),
                new BigDecimal("2000.00"),
                new BigDecimal("100.0000"),
                new BigDecimal("5000.00")
        );
        TodayTotals today = new TodayTotals(new BigDecimal("5.0000"), new BigDecimal("250.00"));

        List<ItemVelocityRow> rows = ItemVelocityMerge.merge(
                List.of(eggs),
                Map.of("item-eggs", today),
                Map.of(),
                50
        );

        assertThat(rows).hasSize(1);
        ItemVelocityRow row = rows.get(0);
        assertThat(row.todayQty()).isEqualByComparingTo("5.0000");
        assertThat(row.todayRevenue()).isEqualByComparingTo("250.00");
        assertThat(row.yesterdayQty()).isEqualByComparingTo("10.0000");
        assertThat(row.last3Qty()).isEqualByComparingTo("25.0000");
        assertThat(row.last3Revenue()).isEqualByComparingTo("1250.00");
        assertThat(row.last7Qty()).isEqualByComparingTo("45.0000");
        assertThat(row.last30Qty()).isEqualByComparingTo("105.0000");
    }

    @Test
    void includesTodayOnlyItemsMissingFromMv() {
        TodayTotals today = new TodayTotals(new BigDecimal("3.0000"), new BigDecimal("90.00"));
        ItemMeta meta = new ItemMeta(
                "item-milk",
                "Milk",
                "MLK-1",
                new BigDecimal("12.0000")
        );

        List<ItemVelocityRow> rows = ItemVelocityMerge.merge(
                List.of(),
                Map.of("item-milk", today),
                Map.of("item-milk", meta),
                50
        );

        assertThat(rows).hasSize(1);
        ItemVelocityRow row = rows.get(0);
        assertThat(row.itemName()).isEqualTo("Milk");
        assertThat(row.todayQty()).isEqualByComparingTo("3.0000");
        assertThat(row.yesterdayQty()).isEqualByComparingTo("0.0000");
        assertThat(row.last30Qty()).isEqualByComparingTo("3.0000");
        assertThat(row.last30Revenue()).isEqualByComparingTo("90.00");
    }

    @Test
    void sortsByLast30DescAndRespectsLimit() {
        PastBuckets a = past("a", "A", "30");
        PastBuckets b = past("b", "B", "10");
        PastBuckets c = past("c", "C", "50");

        List<ItemVelocityRow> rows = ItemVelocityMerge.merge(
                List.of(a, b, c),
                Map.of(),
                Map.of(),
                2
        );

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).itemId()).isEqualTo("c");
        assertThat(rows.get(1).itemId()).isEqualTo("a");
    }

    private static PastBuckets past(String id, String name, String last30Qty) {
        return new PastBuckets(
                id,
                name,
                id,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(last30Qty),
                BigDecimal.ZERO
        );
    }
}
