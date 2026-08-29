package zelisline.ub.onboarding.progress.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingGateService;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class SetupProgressServiceTest {

    @Mock
    private MerchantOnboardingGateService gateService;
    @Mock
    private BusinessRepository businessRepository;
    @Mock
    private BusinessOpsAlertSettingsService opsAlertSettingsService;
    @Mock
    private SupplierProductRepository supplierProductRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private ObjectProvider<SmsCreditService> smsCreditService;

    private SetupProgressService service;

    @BeforeEach
    void setUp() {
        service = new SetupProgressService(
                gateService,
                businessRepository,
                opsAlertSettingsService,
                supplierProductRepository,
                itemRepository,
                new SetupProgressSettingsService(new ObjectMapper()),
                new ObjectMapper(),
                smsCreditService);
    }

    @Test
    void hiddenUntilQuestionnaireDone() {
        Business business = business("biz-1", "{}");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(java.util.Optional.of(business));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot(false, 0, 0, 0, false, false, false));
        when(opsAlertSettingsService.resolveForBusiness("biz-1")).thenReturn(new BusinessOpsAlertSettings());
        when(supplierProductRepository.countActiveLinksForBusiness("biz-1")).thenReturn(0L);
        when(itemRepository.existsActiveVariantByBusinessId("biz-1")).thenReturn(false);

        var response = service.getForBusiness("biz-1");

        assertThat(response.visible()).isFalse();
        assertThat(response.percentComplete()).isZero();
    }

    @Test
    void showsCurrentStockStepAfterQuestionnaire() {
        Business business = business("biz-1", "{}");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(java.util.Optional.of(business));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot(true, 0, 0, 0, false, false, false));
        when(opsAlertSettingsService.resolveForBusiness("biz-1")).thenReturn(new BusinessOpsAlertSettings());
        when(supplierProductRepository.countActiveLinksForBusiness("biz-1")).thenReturn(0L);
        when(itemRepository.existsActiveVariantByBusinessId("biz-1")).thenReturn(false);

        var response = service.getForBusiness("biz-1");

        assertThat(response.visible()).isTrue();
        assertThat(response.currentStepKey()).isEqualTo("stock_shelf");
        assertThat(response.steps().get(1).status()).isEqualTo("current");
        assertThat(response.steps().get(0).status()).isEqualTo("completed");
    }

    @Test
    void awardsCatalogImportPointsForStock() {
        Business business = business("biz-1", "{}");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(java.util.Optional.of(business));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot(true, 3, 2, 0, false, false, false));
        when(opsAlertSettingsService.resolveForBusiness("biz-1")).thenReturn(verifiedPhone());
        when(supplierProductRepository.countActiveLinksForBusiness("biz-1")).thenReturn(0L);
        when(itemRepository.existsActiveVariantByBusinessId("biz-1")).thenReturn(false);

        var response = service.getForBusiness("biz-1");

        assertThat(response.steps().get(1).earnedPoints()).isEqualTo(10);
        assertThat(response.currentStepKey()).isEqualTo("supplier_loop");
    }

    @Test
    void shopReadyWhenRequiredStepsComplete() {
        Business business = business("biz-1", "{\"storefront\":{\"enabled\":false}}");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(java.util.Optional.of(business));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot(true, 5, 0, 1, true, true, true));
        when(opsAlertSettingsService.resolveForBusiness("biz-1")).thenReturn(verifiedPhone());
        when(supplierProductRepository.countActiveLinksForBusiness("biz-1")).thenReturn(2L);
        when(itemRepository.existsActiveVariantByBusinessId("biz-1")).thenReturn(false);
        when(smsCreditService.getIfAvailable()).thenReturn(null);

        var response = service.getForBusiness("biz-1");

        assertThat(response.shopReady()).isTrue();
        assertThat(response.visible()).isFalse();
    }

    @Test
    void grantsSmsBonusOnceWhenShopBecomesReady() {
        Business business = business("biz-1", "{}");
        SmsCreditService credits = org.mockito.Mockito.mock(SmsCreditService.class);
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(java.util.Optional.of(business));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot(true, 5, 0, 1, true, true, true));
        when(opsAlertSettingsService.resolveForBusiness("biz-1")).thenReturn(verifiedPhone());
        when(supplierProductRepository.countActiveLinksForBusiness("biz-1")).thenReturn(2L);
        when(itemRepository.existsActiveVariantByBusinessId("biz-1")).thenReturn(false);
        when(smsCreditService.getIfAvailable()).thenReturn(credits);
        when(credits.grant(
                org.mockito.ArgumentMatchers.eq("biz-1"),
                org.mockito.ArgumentMatchers.eq(25),
                org.mockito.ArgumentMatchers.eq("setup_complete_bonus"),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(25);
        when(businessRepository.save(business)).thenReturn(business);

        var response = service.getForBusiness("biz-1");

        assertThat(response.shopReady()).isTrue();
        assertThat(response.reward()).isNotNull();
        assertThat(response.reward().justGranted()).isTrue();
        assertThat(response.reward().smsCredits()).isEqualTo(25);
        assertThat(business.getSettings()).contains("rewardGrantedAt");
    }

    private static Business business(String id, String settings) {
        Business b = new Business();
        b.setId(id);
        b.setSettings(settings);
        return b;
    }

    private static BusinessOpsAlertSettings verifiedPhone() {
        BusinessOpsAlertSettings s = new BusinessOpsAlertSettings();
        s.setPhone("+254700000000");
        s.setPhoneVerifiedAt(java.time.Instant.now());
        return s;
    }

    private static MerchantOnboardingGateService.Snapshot snapshot(
            boolean questionnaireDone,
            long sellable,
            long catalogImports,
            long suppliers,
            boolean supply,
            boolean sale,
            boolean staff) {
        return new MerchantOnboardingGateService.Snapshot(
                sellable,
                catalogImports,
                suppliers,
                sale ? 1L : 0L,
                supply,
                sale,
                false,
                questionnaireDone,
                questionnaireDone ? "completed" : "pending",
                null,
                false,
                staff,
                false,
                java.time.ZoneId.of("Africa/Nairobi"));
    }
}
