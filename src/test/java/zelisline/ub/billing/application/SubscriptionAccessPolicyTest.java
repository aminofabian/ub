package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;

class SubscriptionAccessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final SubscriptionBillingSettingsService settings =
            mock(SubscriptionBillingSettingsService.class);
    private final SubscriptionAccessPolicy policy = new SubscriptionAccessPolicy(settings);

    @Test
    void blocksOnlyWhenBillingAndAutoBlockAreOn() {
        given(settings.isBillingEnabled()).willReturn(true);
        given(settings.isAutoBlockEnabled()).willReturn(true);
        assertThat(policy.isAccessBlockingEnabled()).isTrue();

        given(settings.isAutoBlockEnabled()).willReturn(false);
        assertThat(policy.isAccessBlockingEnabled()).isFalse();

        given(settings.isBillingEnabled()).willReturn(false);
        given(settings.isAutoBlockEnabled()).willReturn(true);
        assertThat(policy.isAccessBlockingEnabled()).isFalse();
    }

    @Test
    void failsClosedWhenSettingsCannotBeRead() {
        given(settings.isBillingEnabled()).willThrow(new IllegalStateException("db down"));
        assertThat(policy.isAccessBlockingEnabled()).isTrue();
    }

    @Test
    void suspendedTenantIsSoftenedWhenAutoBlockIsOff() {
        given(settings.isBillingEnabled()).willReturn(true);
        given(settings.isAutoBlockEnabled()).willReturn(false);
        assertThat(policy.isSuspendedTenantBlocked(TenantStatus.SUSPENDED)).isFalse();

        given(settings.isAutoBlockEnabled()).willReturn(true);
        assertThat(policy.isSuspendedTenantBlocked(TenantStatus.SUSPENDED)).isTrue();
        assertThat(policy.isSuspendedTenantBlocked(TenantStatus.INACTIVE)).isFalse();
    }

    @Test
    void expiredGraceIsBlockedEvenBeforeTheSchedulerRuns() {
        given(settings.isBillingEnabled()).willReturn(true);
        given(settings.isAutoBlockEnabled()).willReturn(true);

        Business business = new Business();
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
        business.setGraceEndsAt(NOW.minusSeconds(60));
        assertThat(policy.isAccessBlocked(business, NOW)).isTrue();

        business.setGraceEndsAt(NOW.plusSeconds(60));
        assertThat(policy.isAccessBlocked(business, NOW)).isFalse();

        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.SUSPENDED);
        assertThat(policy.isAccessBlocked(business, NOW)).isTrue();
    }

    @Test
    void manualTenantDeactivationIsNotSoftened() {
        given(settings.isBillingEnabled()).willReturn(true);
        given(settings.isAutoBlockEnabled()).willReturn(false);

        Business business = new Business();
        business.setActive(false);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
        assertThat(policy.isSuspendedTenantBlocked(TenantStatus.INACTIVE)).isFalse();
        // The caller treats INACTIVE separately; the policy only softens
        // billing SUSPENDED, never a manual deactivation.
        assertThat(policy.isAccessBlocked(business, NOW)).isFalse();
    }

    @Test
    void recoveryPathsStayReachable() {
        assertThat(policy.isBillingRecoveryPath("/api/v1/me")).isTrue();
        assertThat(policy.isBillingRecoveryPath("/api/v1/subscription/billing-status")).isTrue();
        assertThat(policy.isBillingRecoveryPath("/api/v1/auth/refresh")).isTrue();
        assertThat(policy.isBillingRecoveryPath("/api/v1/sales")).isFalse();
    }
}
