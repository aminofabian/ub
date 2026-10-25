package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.domain.Business;

@ExtendWith(MockitoExtension.class)
class SubscriptionBillingEligibilityServiceTest {

    @Mock
    private ItemRepository itemRepository;
    @Mock
    private SaleRepository saleRepository;
    @Mock
    private SubscriptionBillingSettingsService settingsService;

    @InjectMocks
    private SubscriptionBillingEligibilityService service;

    @Test
    void freeTierIsNeverBillable() {
        Business business = business("free");

        assertThat(service.isBillable(business)).isFalse();
        verify(itemRepository, never()).countByBusinessIdAndDeletedAtIsNullAndActiveTrue(anyId());
    }

    @Test
    void demoSlugIsNeverBillable() {
        Business business = business("starter");
        business.setSlug("demo");

        assertThat(service.isBillable(business)).isFalse();
        verify(itemRepository, never()).countByBusinessIdAndDeletedAtIsNullAndActiveTrue(anyId());
    }

    @Test
    void freeSizedCatalogWithNoSalesIsNotBillable() {
        Business business = business("starter");
        givenStarterPlan();
        given(itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(business.getId()))
                .willReturn(300L);
        given(saleRepository.existsByBusinessIdAndStatusAndVoidedAtIsNull(
                business.getId(), SalesConstants.SALE_STATUS_COMPLETED)).willReturn(false);

        assertThat(service.isBillable(business)).isFalse();
    }

    @Test
    void catalogOverFreeLimitIsBillableEvenWithoutSales() {
        Business business = business("starter");
        givenStarterPlan();
        given(itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(business.getId()))
                .willReturn(301L);

        assertThat(service.isBillable(business)).isTrue();
    }

    @Test
    void aCompletedSaleMakesTheShopBillable() {
        Business business = business("starter");
        givenStarterPlan();
        given(itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(business.getId()))
                .willReturn(0L);
        given(saleRepository.existsByBusinessIdAndStatusAndVoidedAtIsNull(
                business.getId(), SalesConstants.SALE_STATUS_COMPLETED)).willReturn(true);

        assertThat(service.isBillable(business)).isTrue();
    }

    @Test
    void zeroPricedPlanIsNotBillable() {
        Business business = business("trial");
        PlatformSubscriptionPlan trial = plan("trial", "Trial", BigDecimal.ZERO, 1000);
        given(settingsService.planOrNull("trial")).willReturn(trial);

        assertThat(service.isBillable(business)).isFalse();
        verify(itemRepository, never()).countByBusinessIdAndDeletedAtIsNullAndActiveTrue(anyId());
    }

    private void givenStarterPlan() {
        given(settingsService.planOrNull("starter"))
                .willReturn(plan("starter", "Starter", new BigDecimal("300.00"), 1000));
        given(settingsService.planOrNull("free"))
                .willReturn(plan("free", "Free", BigDecimal.ZERO, 300));
    }

    private static PlatformSubscriptionPlan plan(
            String code, String name, BigDecimal price, Integer productLimit) {
        PlatformSubscriptionPlan plan = new PlatformSubscriptionPlan();
        plan.setTierCode(code);
        plan.setDisplayName(name);
        plan.setMonthlyPriceKes(price);
        plan.setProductLimit(productLimit);
        return plan;
    }

    private static Business business(String tier) {
        Business business = new Business();
        business.setId("business-1");
        business.setName("Test Shop");
        business.setSlug("test-shop");
        business.setSubscriptionTier(tier);
        return business;
    }

    private static String anyId() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
