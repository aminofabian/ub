package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.sales.api.dto.SalesByHourResponse;
import zelisline.ub.sales.application.HourlySalesBuckets.Stamp;

class HourlySalesBucketsTest {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Test
    void emptyDayHasNoHours() {
        SalesByHourResponse out = HourlySalesBuckets.build(List.of(), NAIROBI);
        assertThat(out.saleCount()).isZero();
        assertThat(out.hours()).isEmpty();
        assertThat(out.timezone()).isEqualTo("Africa/Nairobi");
    }

    @Test
    void bucketsInNairobiAndFillsTheQuietHourBetween() {
        // 04:30Z = 07:30 EAT, 06:05Z = 09:05 EAT.
        SalesByHourResponse out = HourlySalesBuckets.build(List.of(
                stamp("a", "2026-09-27T04:30:00Z", "20"),
                stamp("b", "2026-09-27T06:05:00Z", "100"),
                stamp("c", "2026-09-27T04:50:00Z", "5")
        ), NAIROBI);

        assertThat(out.saleCount()).isEqualTo(3);
        assertThat(out.revenue()).isEqualByComparingTo("125");
        assertThat(out.hours()).extracting(row -> row.hour()).containsExactly(7, 8, 9);
        assertThat(out.hours().get(0).saleCount()).isEqualTo(2);
        assertThat(out.hours().get(0).revenue()).isEqualByComparingTo("25");
        assertThat(out.hours().get(0).sales()).extracting(sale -> sale.saleId()).containsExactly("a", "c");
        assertThat(out.hours().get(1).saleCount()).isZero();
        assertThat(out.hours().get(1).sales()).isEmpty();
        assertThat(out.hours().get(2).saleCount()).isEqualTo(1);
    }

    @Test
    void keepsTheHourCountWhenTheReceiptListIsCapped() {
        List<Stamp> stamps = new ArrayList<>();
        for (int i = 0; i < HourlySalesBuckets.DETAIL_LIMIT + 3; i++) {
            stamps.add(stamp("s" + i, "2026-09-27T05:00:00Z", "10"));
        }
        SalesByHourResponse out = HourlySalesBuckets.build(stamps, NAIROBI);
        assertThat(out.hours()).hasSize(1);
        assertThat(out.hours().get(0).hour()).isEqualTo(8);
        assertThat(out.hours().get(0).saleCount()).isEqualTo(HourlySalesBuckets.DETAIL_LIMIT + 3);
        assertThat(out.hours().get(0).sales()).hasSize(HourlySalesBuckets.DETAIL_LIMIT);
        assertThat(out.hours().get(0).omitted()).isEqualTo(3);
        assertThat(out.hours().get(0).revenue()).isEqualByComparingTo(
                String.valueOf((HourlySalesBuckets.DETAIL_LIMIT + 3) * 10));
    }

    private static Stamp stamp(String id, String instant, String amount) {
        return new Stamp(id, 1L, Instant.parse(instant), "Agnes", "cash", new BigDecimal(amount));
    }
}
