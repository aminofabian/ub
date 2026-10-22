package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.billing.domain.PlatformSubscriptionBillingSettings;
import zelisline.ub.billing.repository.PlatformSubscriptionBillingSettingsRepository;
import zelisline.ub.billing.repository.PlatformSubscriptionPlanRepository;
import zelisline.ub.identity.application.FrontendAuthLinkBuilder;

class SubscriptionBillingSettingsServiceRenewalUrlTest {

    private final PlatformSubscriptionBillingSettingsRepository settingsRepository =
            mock(PlatformSubscriptionBillingSettingsRepository.class);
    private final PlatformSubscriptionPlanRepository planRepository =
            mock(PlatformSubscriptionPlanRepository.class);
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder = mock(FrontendAuthLinkBuilder.class);

    private SubscriptionBillingSettingsService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionBillingSettingsService(
                settingsRepository, planRepository, frontendAuthLinkBuilder);
        PlatformSubscriptionBillingSettings row = new PlatformSubscriptionBillingSettings();
        row.setRenewalBaseUrl("https://palmart.co.ke/business/billing/renew");
        given(settingsRepository.findFirstByOrderById()).willReturn(Optional.of(row));
    }

    @Test
    void resolveRenewalUrlUsesTenantOriginNotPlatformHost() {
        given(frontendAuthLinkBuilder.tenantOrigin("biz-zel")).willReturn("https://zel.kiosk.ke");

        assertThat(service.resolveRenewalUrl("biz-zel"))
                .isEqualTo("https://zel.kiosk.ke/business/billing/renew");
    }

    @Test
    void resolveShopOriginDelegatesToTenantOrigin() {
        given(frontendAuthLinkBuilder.tenantOrigin("biz-zel")).willReturn("https://zel.kiosk.ke");

        assertThat(service.resolveShopOrigin("biz-zel")).isEqualTo("https://zel.kiosk.ke");
    }
}
