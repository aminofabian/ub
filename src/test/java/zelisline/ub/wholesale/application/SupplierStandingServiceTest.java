package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.trade.repository.TradeOrderLineRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.api.dto.SupplierStandingResponse;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

class SupplierStandingServiceTest {

    private static final String BUSINESS = "biz-1";

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final SalesOrderLineRepository lines = mock(SalesOrderLineRepository.class);
    private final MarketplaceSupplierRepository passports = mock(MarketplaceSupplierRepository.class);
    private final TradeOrderRepository trades = mock(TradeOrderRepository.class);
    private final TradeOrderLineRepository tradeLines = mock(TradeOrderLineRepository.class);
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final BusinessOnboardingSettingsService onboarding = mock(BusinessOnboardingSettingsService.class);

    private final SupplierStandingService service = new SupplierStandingService(
            flags, orders, lines, passports, trades, tradeLines, businesses, onboarding,
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void flagsOff_isOffAndReadsNothing() {
        SupplierStandingResponse out = service.standing(BUSINESS, 90);

        assertThat(out.enabled()).isFalse();
        assertThat(out.state()).isEqualTo("off");
        assertThat(out.measures()).isEmpty();
        verifyNoInteractions(orders, lines, passports, trades, tradeLines, businesses, onboarding);
    }

    @Test
    void belowTheSampleFloor_isNewAndShowsNoMeasures() {
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)).thenReturn(true);
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_STANDING)).thenReturn(true);
        when(orders.findByBusinessIdAndAnsweredAtGreaterThanEqual(eq(BUSINESS), any())).thenReturn(List.of());

        SupplierStandingResponse out = service.standing(BUSINESS, 90);

        assertThat(out.enabled()).isTrue();
        assertThat(out.state()).isEqualTo("new");
        assertThat(out.answeredOrders()).isZero();
        assertThat(out.measures()).isEmpty();
        assertThat(out.dispatchLeadWorkingDays()).isEqualTo(2);
        assertThat(out.closedDays()).isEmpty();
    }
}
