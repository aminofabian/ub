package zelisline.ub.wholesale.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingGateService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.api.dto.SupplierReadinessResponse;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierMilestoneRepository;

class SupplierReadinessServiceTest {

    private static final String BUSINESS = "biz-1";

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final MerchantOnboardingGateService gate = mock(MerchantOnboardingGateService.class);
    private final MarketplaceSupplierRepository passports = mock(MarketplaceSupplierRepository.class);
    private final SupplierUserRepository users = mock(SupplierUserRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final SupplierMilestoneRepository milestones = mock(SupplierMilestoneRepository.class);

    private final SupplierReadinessService service =
            new SupplierReadinessService(flags, gate, passports, users, orders, milestones);

    @Test
    void flagsOff_returnsDisabledAndTouchesNoData() {
        SupplierReadinessResponse out = service.readiness(BUSINESS);

        assertFalse(out.enabled());
        assertTrue(out.steps().isEmpty());
        verifyNoInteractions(passports, users, orders, gate, milestones);
    }

    @Test
    void flagsOn_noPassportNoOrders_noStepIsDone() {
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)).thenReturn(true);
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_READINESS)).thenReturn(true);
        when(passports.findFirstByBusinessId(BUSINESS)).thenReturn(Optional.empty());
        when(orders.existsByBusinessId(BUSINESS)).thenReturn(false);
        MerchantOnboardingGateService.Snapshot snap = mock(MerchantOnboardingGateService.Snapshot.class);
        when(snap.sellableSkuCount()).thenReturn(0L);
        when(gate.snapshot(BUSINESS)).thenReturn(snap);

        SupplierReadinessResponse out = service.readiness(BUSINESS);

        assertTrue(out.enabled());
        assertEquals(
                List.of("claim_page", "open_shop", "payout", "delivery_areas", "first_product", "first_order"),
                out.steps().stream().map(SupplierReadinessResponse.Step::key).toList());
        assertTrue(out.steps().stream().noneMatch(SupplierReadinessResponse.Step::done));
    }
}
