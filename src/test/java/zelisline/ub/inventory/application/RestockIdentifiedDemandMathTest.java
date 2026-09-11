package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.inventory.application.RestockIdentifiedDemandMath.Demand;
import zelisline.ub.inventory.application.RestockIdentifiedDemandMath.PurchaseDay;

class RestockIdentifiedDemandMathTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 11);

    @Test
    void explainUsesShopLanguage() {
        assertThat(RestockIdentifiedDemandMath.explain(12, 14, 4))
                .isEqualTo("12 regulars usually buy this every ~14 days; 4 are due this week");
        assertThat(RestockIdentifiedDemandMath.explain(1, 7, 1))
                .isEqualTo("1 regular usually buys this every ~7 days; 1 is due this week");
        assertThat(RestockIdentifiedDemandMath.explain(0, 14, 0)).isNull();
    }

    @Test
    void dueThisWeekIncludesOverdueAndUpcoming() {
        assertThat(RestockIdentifiedDemandMath.dueThisWeek(20, 14)).isTrue();
        assertThat(RestockIdentifiedDemandMath.dueThisWeek(16, 14)).isTrue();
        assertThat(RestockIdentifiedDemandMath.dueThisWeek(12, 14)).isTrue();
        assertThat(RestockIdentifiedDemandMath.dueThisWeek(1, 14)).isFalse();
        assertThat(RestockIdentifiedDemandMath.dueThisWeek(0, 14)).isFalse();
    }

    @Test
    void belowCaptureThresholdDoesNotNudgeQty() {
        List<PurchaseDay> days = fortnightRegular("c1", AS_OF.minusDays(16), 4);
        Demand d = RestockIdentifiedDemandMath.compute(
                days,
                new BigDecimal("10"),
                new BigDecimal("100"),
                AS_OF);
        assertThat(d.regularCount()).isEqualTo(1);
        assertThat(d.dueThisWeek()).isEqualTo(1);
        assertThat(d.capturePct()).isEqualByComparingTo("0.1000");
        assertThat(d.shouldNudge(new BigDecimal("2"))).isFalse();
        assertThat(d.explain()).contains("1 regular").contains("due this week");
    }

    @Test
    void highCaptureNudgesWhenDueQtyExceedsVelocity() {
        List<PurchaseDay> days = new ArrayList<>();
        days.addAll(fortnightRegular("a", AS_OF.minusDays(16), 4));
        days.addAll(fortnightRegular("b", AS_OF.minusDays(15), 4));
        Demand d = RestockIdentifiedDemandMath.compute(
                days,
                new BigDecimal("40"),
                new BigDecimal("100"),
                AS_OF);
        assertThat(d.regularCount()).isEqualTo(2);
        assertThat(d.dueThisWeek()).isEqualTo(2);
        assertThat(d.capturePct()).isEqualByComparingTo("0.4000");
        assertThat(d.identifiedDueQty()).isEqualByComparingTo("2.0000");
        assertThat(d.shouldNudge(new BigDecimal("1"))).isTrue();
        assertThat(d.shouldNudge(new BigDecimal("2"))).isFalse();
        assertThat(d.shouldNudge(new BigDecimal("99"))).isFalse();
    }

    @Test
    void fewerThanThreePurchaseDaysIsNotARegular() {
        List<PurchaseDay> days = List.of(
                new PurchaseDay("c1", AS_OF.minusDays(20), BigDecimal.ONE),
                new PurchaseDay("c1", AS_OF.minusDays(6), BigDecimal.ONE));
        Demand d = RestockIdentifiedDemandMath.compute(days, BigDecimal.TEN, BigDecimal.TEN, AS_OF);
        assertThat(d.regularCount()).isZero();
        assertThat(d.explain()).isNull();
        assertThat(d.shouldNudge(BigDecimal.ONE)).isFalse();
    }

    private static List<PurchaseDay> fortnightRegular(String customerId, LocalDate last, int visits) {
        List<PurchaseDay> out = new ArrayList<>();
        LocalDate cursor = last;
        for (int i = 0; i < visits; i++) {
            out.add(new PurchaseDay(customerId, cursor, BigDecimal.ONE));
            cursor = cursor.minusDays(14);
        }
        return out;
    }
}
