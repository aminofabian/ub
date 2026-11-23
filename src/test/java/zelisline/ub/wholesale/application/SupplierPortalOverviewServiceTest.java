package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.wholesale.api.dto.PortalOverviewResponse;

class SupplierPortalOverviewServiceTest {

    /** Monday 23 November 2026, 12:00 in Nairobi. */
    private static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");

    private static PurchaseOrder order(String shop, Instant sent, Instant answered) {
        PurchaseOrder po = new PurchaseOrder();
        po.setBusinessId(shop);
        po.setSentToSupplierAt(sent);
        po.setSupplierResponseAt(answered);
        return po;
    }

    @Test
    void ordersNotAnsweredYetAreTheNextThing() {
        PortalOverviewResponse overview = SupplierPortalOverviewService.describe(List.of(
                order("shop-a", NOW.minusSeconds(7200), null),
                order("shop-b", NOW.minusSeconds(3600), null)), NOW);

        assertThat(overview.waitingToAnswer()).isEqualTo(2);
        assertThat(overview.oldestWaitingSince()).isEqualTo(NOW.minusSeconds(7200));
        assertThat(overview.nextThing().text()).isEqualTo("2 orders are waiting for your answer");
        assertThat(overview.state()).isEqualTo("new");
        assertThat(overview.medianAnswerWorkingHours()).isNull();
    }

    @Test
    void tooFewShopsSaysNothingAboutAnswerTimes() {
        List<PurchaseOrder> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(order("shop-a", NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        }

        PortalOverviewResponse overview = SupplierPortalOverviewService.describe(orders, NOW);

        assertThat(overview.answeredOrders()).isEqualTo(12);
        assertThat(overview.shops()).isEqualTo(1);
        assertThat(overview.state()).isEqualTo("new");
        assertThat(overview.medianAnswerWorkingHours()).isNull();
        assertThat(overview.milestones()).contains("First order answered", "10 orders answered",
                "First shop to order from you again");
    }

    @Test
    void enoughOrdersFromEnoughShopsGivesTheMedianAnswerTime() {
        List<PurchaseOrder> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // Sent at 10:00 and answered at 11:00 the same Monday: one working hour.
            orders.add(order("shop-" + (i % 3), NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        }

        PortalOverviewResponse overview = SupplierPortalOverviewService.describe(orders, NOW);

        assertThat(overview.state()).isEqualTo("measured");
        assertThat(overview.shops()).isEqualTo(3);
        assertThat(overview.medianAnswerWorkingHours()).isEqualByComparingTo("1.0");
        assertThat(overview.nextThing()).isNull();
    }
}
