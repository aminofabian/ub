package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;

class SubscriptionPlanLimitExperienceTest {

    @Test
    void experienceWindowCoversFirstThirtyDaysFromCreation() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(
                created, created.plus(1, ChronoUnit.DAYS))).isTrue();
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(
                created, created.plus(29, ChronoUnit.DAYS))).isTrue();
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(
                created, created.plus(30, ChronoUnit.DAYS).minusSeconds(1))).isTrue();
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(
                created, created.plus(30, ChronoUnit.DAYS))).isFalse();
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(
                created, created.plus(45, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    void missingTimestampsFailOpenSoDayOneNeverNags() {
        Instant now = Instant.parse("2026-01-15T00:00:00Z");
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(null, now)).isTrue();
        assertThat(SubscriptionBillingService.isWithinPlanLimitExperience(now, null)).isTrue();
    }

    @Test
    void deferredPlanFitViewKeepsCountsButClearsUpgradeSignals() {
        SubscriptionPlanFitService service = new SubscriptionPlanFitService(null, null, null, null);
        SubscriptionPlanFit.Result overStarter = SubscriptionPlanFit.evaluate(
                new SubscriptionPlanFit.Usage(3142, 2),
                new SubscriptionPlanFit.PlanSnapshot(
                        "starter", "Starter", 1000, 3, 1, BigDecimal.valueOf(300)),
                List.of(
                        new SubscriptionPlanFit.PlanSnapshot(
                                "starter", "Starter", 1000, 3, 1, BigDecimal.valueOf(300)),
                        new SubscriptionPlanFit.PlanSnapshot(
                                "growth", "Growth", 5000, 10, 3, BigDecimal.valueOf(1500))));

        assertThat(overStarter.needsUpgrade()).isTrue();

        SubscriptionBillingDtos.PlanFitView deferred = service.toView(overStarter, false);
        assertThat(deferred.productCount()).isEqualTo(3142);
        assertThat(deferred.productLimit()).isEqualTo(1000);
        assertThat(deferred.needsUpgrade()).isFalse();
        assertThat(deferred.overProductLimit()).isFalse();
        assertThat(deferred.recommendedTier()).isNull();
        assertThat(deferred.reasons()).isEmpty();

        SubscriptionBillingDtos.PlanFitView enforced = service.toView(overStarter, true);
        assertThat(enforced.needsUpgrade()).isTrue();
        assertThat(enforced.recommendedTier()).isEqualTo("growth");
    }
}
