package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zelisline.ub.sales.application.CashDrawerOpenMatcher.CashSale;
import zelisline.ub.sales.application.CashDrawerOpenMatcher.Drawout;
import zelisline.ub.sales.application.CashDrawerOpenMatcher.Hit;
import zelisline.ub.sales.application.CashDrawerOpenMatcher.Opening;

class CashDrawerOpenMatcherTest {

    private static final Instant AT = Instant.parse("2026-11-21T15:00:00Z");

    @Test
    void aNamedSaleMatchesEvenWhenTheReceiptIsOlderThanTheWindow() {
        Opening opening = new Opening("open-1", AT, "sale-1");
        CashSale sale = cash("sale-1", "Receipt 12", AT.minus(CashDrawerOpenMatcher.WINDOW).minusSeconds(60), "80");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(List.of(opening), List.of(sale), List.of());

        assertThat(hits.get("open-1").kind()).isEqualTo(CashDrawerOpenMatcher.KIND_CASH);
        assertThat(hits.get("open-1").label()).isEqualTo("Receipt 12");
    }

    @Test
    void aReprintOfTheSameSaleStaysUnmatched() {
        Opening first = new Opening("open-1", AT, "sale-1");
        Opening reprint = new Opening("open-2", AT.plusSeconds(30), "sale-1");
        CashSale sale = cash("sale-1", "Receipt 12", AT, "80");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(
                List.of(reprint, first),
                List.of(sale),
                List.of());

        assertThat(hits).containsOnlyKeys("open-1");
        assertThat(hits.get("open-1").amount()).isEqualByComparingTo("80");
    }

    @Test
    void theNearestCashSaleInsideTwoMinutesWinsOverADrawout() {
        Opening opening = new Opening("open-1", AT, null);
        CashSale far = cash("sale-far", "Receipt 1", AT.plusSeconds(90), "40");
        CashSale near = cash("sale-near", "Receipt 2", AT.plusSeconds(10), "15");
        Drawout drawout = new Drawout("draw-1", AT.plusSeconds(1), new BigDecimal("500"), "Milk");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(
                List.of(opening),
                List.of(far, near),
                List.of(drawout));

        assertThat(hits.get("open-1").kind()).isEqualTo(CashDrawerOpenMatcher.KIND_CASH);
        assertThat(hits.get("open-1").label()).isEqualTo("Receipt 2");
    }

    @Test
    void cashOutsideTheWindowDoesNotMatchAndADrawoutDoes() {
        Opening opening = new Opening("open-1", AT, null);
        CashSale late = cash("sale-1", "Receipt 9", AT.plus(CashDrawerOpenMatcher.WINDOW).plusMillis(1), "40");
        Drawout drawout = new Drawout("draw-1", AT.plusSeconds(20), new BigDecimal("200"), "Float");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(List.of(opening), List.of(late), List.of(drawout));

        assertThat(hits.get("open-1").kind()).isEqualTo(CashDrawerOpenMatcher.KIND_DRAWOUT);
        assertThat(hits.get("open-1").label()).isEqualTo("Float");
    }

    @Test
    void oneDrawoutExplainsOnlyOneOpening() {
        Opening first = new Opening("open-1", AT, null);
        Opening second = new Opening("open-2", AT.plusSeconds(15), null);
        Drawout drawout = new Drawout("draw-1", AT.plusSeconds(5), new BigDecimal("50"), "Change");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(List.of(first, second), List.of(), List.of(drawout));

        assertThat(hits).containsOnlyKeys("open-1");
    }

    @Test
    void anOpeningOnTheWindowEdgeStillMatches() {
        Opening opening = new Opening("open-1", AT, null);
        CashSale sale = cash("sale-1", "Receipt 3", AT.plus(CashDrawerOpenMatcher.WINDOW), "10");

        Map<String, Hit> hits = CashDrawerOpenMatcher.match(List.of(opening), List.of(sale), List.of());

        assertThat(hits).containsKey("open-1");
    }

    private static CashSale cash(String saleId, String label, Instant soldAt, String amount) {
        return new CashSale(saleId, label, soldAt, new BigDecimal(amount));
    }
}
