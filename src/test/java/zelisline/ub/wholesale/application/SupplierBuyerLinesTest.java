package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.trade.repository.TradeOrderLineRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.api.dto.BuyerLinesResponse;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

class SupplierBuyerLinesTest {

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
            flags, orders, lines, passports, trades, tradeLines, businesses, onboarding, new ObjectMapper());

    @Test
    void buyerLinesStayOffWhileTheBuyerLinesFlagIsOff() {
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)).thenReturn(true);
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_BUYER_LINES)).thenReturn(false);

        BuyerLinesResponse response = service.buyerLines(BUSINESS);

        assertThat(response.enabled()).isFalse();
        assertThat(response.lines()).isEmpty();
        verifyNoInteractions(orders, passports, trades);
    }

    @Test
    void noLineIsShownBelowTheSampleFloor() {
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)).thenReturn(true);
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_BUYER_LINES)).thenReturn(true);
        when(orders.findByBusinessIdAndAnsweredAtGreaterThanEqual(eq(BUSINESS), any())).thenReturn(List.of());

        BuyerLinesResponse response = service.buyerLines(BUSINESS);

        assertThat(response.enabled()).isTrue();
        assertThat(response.orders()).isZero();
        assertThat(response.lines()).isEmpty();
    }
}
