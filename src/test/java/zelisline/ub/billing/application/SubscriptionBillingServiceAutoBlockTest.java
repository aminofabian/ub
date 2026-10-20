package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.billing.repository.SubscriptionRenewalOrderRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class SubscriptionBillingServiceAutoBlockTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final SubscriptionBillingSettingsService settings = mock(SubscriptionBillingSettingsService.class);
    private final SubscriptionAccessPolicy accessPolicy = mock(SubscriptionAccessPolicy.class);
    private final SubscriptionRenewalOrderRepository orderRepository =
            mock(SubscriptionRenewalOrderRepository.class);
    private final UserSessionRepository sessionRepository = mock(UserSessionRepository.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final AuditEventBuilder auditBuilder = mock(AuditEventBuilder.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubscriptionExpiryCampaignService> expiryCampaigns =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubscriptionPlanFitService> planFit = mock(ObjectProvider.class);

    private final SubscriptionBillingService service = new SubscriptionBillingService(
            businessRepository,
            settings,
            accessPolicy,
            orderRepository,
            sessionRepository,
            auditPublisher,
            auditBuilder,
            expiryCampaigns,
            planFit);

    @Test
    void doesNotSuspendWhenAutoBlockIsOff() {
        given(settings.isBillingEnabled()).willReturn(true);
        given(accessPolicy.isAccessBlockingEnabled()).willReturn(false);

        Business business = new Business();
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
        business.setGraceEndsAt(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(service.suspendIfGraceEnded(business, Instant.parse("2026-10-20T00:00:00Z")))
                .isFalse();
        verify(businessRepository, never()).findByIdAndDeletedAtIsNullForUpdate(business.getId());
        verify(businessRepository, never()).save(business);
    }
}
