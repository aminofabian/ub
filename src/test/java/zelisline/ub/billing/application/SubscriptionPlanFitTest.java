package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SubscriptionPlanFitTest {

    private static final List<SubscriptionPlanFit.PlanSnapshot> CATALOGUE = List.of(
            plan("free", "Free", 300, 1, 0, 0),
            plan("starter", "Starter", 1000, 3, 1, 300),
            plan("business", "Business", 2500, 5, 2, 800),
            plan("growth", "Growth", 5000, 10, 3, 1500),
            plan("enterprise", "Enterprise", null, null, 4, 3000));

    @Test
    void starterShopWith3000ProductsRecommendsGrowth() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(3142, 2),
                plan("starter", "Starter", 1000, 3, 1, 300),
                CATALOGUE);

        assertThat(result.needsUpgrade()).isTrue();
        assertThat(result.overProductLimit()).isTrue();
        assertThat(result.overUserLimit()).isFalse();
        assertThat(result.negotiable()).isFalse();
        assertThat(result.talkToUs()).isFalse();
        assertThat(result.recommended().tierCode()).isEqualTo("growth");
        assertThat(result.reasons()).anyMatch(r -> r.contains("3,142 products"));
        assertThat(SubscriptionPlanFit.blockProductMessage(result))
                .contains("Switch to Growth")
                .contains("1,000 products");
    }

    @Test
    void freeShopStaysOnFreeWhenUnderCaps() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(120, 1),
                plan("free", "Free", 300, 1, 0, 0),
                CATALOGUE);

        assertThat(result.needsUpgrade()).isFalse();
        assertThat(result.recommended().tierCode()).isEqualTo("free");
    }

    @Test
    void secondStaffOnFreeRecommendsStarter() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(40, 2),
                plan("free", "Free", 300, 1, 0, 0),
                CATALOGUE);

        assertThat(result.needsUpgrade()).isTrue();
        assertThat(result.overUserLimit()).isTrue();
        assertThat(result.recommended().tierCode()).isEqualTo("starter");
        assertThat(SubscriptionPlanFit.blockUserMessage(result)).contains("Switch to Starter");
    }

    @Test
    void doesNotSuggestDowngradeWhenAlreadyOnAFittingHigherPlan() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(80, 1),
                plan("business", "Business", 2500, 5, 2, 800),
                CATALOGUE);

        assertThat(result.needsUpgrade()).isFalse();
        assertThat(result.recommended().tierCode()).isEqualTo("free");
        assertThat(SubscriptionPlanFit.fits(result.usage(), result.current())).isTrue();
    }

    @Test
    void usagePastEveryPublishedCapIsNegotiable() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(80_000, 40),
                plan("enterprise", "Enterprise", 20_000, 20, 4, 3000),
                List.of(
                        plan("free", "Free", 300, 1, 0, 0),
                        plan("starter", "Starter", 1000, 3, 1, 300),
                        plan("enterprise", "Enterprise", 20_000, 20, 4, 3000)));

        assertThat(result.needsUpgrade()).isTrue();
        assertThat(result.negotiable()).isTrue();
        assertThat(result.talkToUs()).isTrue();
        assertThat(result.recommended()).isNull();
        assertThat(SubscriptionPlanFit.blockProductMessage(result)).contains("Talk to us");
    }

    @Test
    void enterpriseRecommendationIsTalkToUs() {
        SubscriptionPlanFit.Result result = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(12_000, 8),
                plan("growth", "Growth", 5000, 10, 3, 1500),
                CATALOGUE);

        assertThat(result.recommended().tierCode()).isEqualTo("enterprise");
        assertThat(result.talkToUs()).isTrue();
        assertThat(result.negotiable()).isFalse();
    }

    @Test
    void inclusiveLimitAllowsExactCap() {
        assertThat(SubscriptionPlanFit.fits(
                new SubscriptionPlanFit.Usage(300, 1),
                plan("free", "Free", 300, 1, 0, 0))).isTrue();
        assertThat(SubscriptionPlanFit.fits(
                new SubscriptionPlanFit.Usage(301, 1),
                plan("free", "Free", 300, 1, 0, 0))).isFalse();
    }

    private static SubscriptionPlanFit.PlanSnapshot plan(
            String code,
            String name,
            Integer products,
            Integer users,
            int sort,
            int price
    ) {
        return new SubscriptionPlanFit.PlanSnapshot(
                code, name, products, users, sort, BigDecimal.valueOf(price));
    }
}
