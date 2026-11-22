package zelisline.ub.wholesale.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.grocery.application.GroceryInvoiceService;
import zelisline.ub.grocery.repository.GroceryInvoiceLineRepository;
import zelisline.ub.grocery.repository.GroceryInvoiceRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

class CounterOrderServiceTest {

    private static final String BUSINESS = "biz-1";

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final TradeAccountService accounts = mock(TradeAccountService.class);
    private final SalesOrderService salesOrders = mock(SalesOrderService.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final SalesOrderLineRepository lines = mock(SalesOrderLineRepository.class);
    private final SalesInvoiceRepository invoices = mock(SalesInvoiceRepository.class);
    private final GroceryInvoiceRepository counterInvoices = mock(GroceryInvoiceRepository.class);
    private final GroceryInvoiceLineRepository counterLines = mock(GroceryInvoiceLineRepository.class);
    private final GroceryInvoiceService counter = mock(GroceryInvoiceService.class);
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final zelisline.ub.wholesale.repository.TradeAccountRepository accountRows =
            mock(zelisline.ub.wholesale.repository.TradeAccountRepository.class);
    private final zelisline.ub.pricing.application.PricingService pricing =
            mock(zelisline.ub.pricing.application.PricingService.class);

    private final CounterOrderService service = new CounterOrderService(
            flags, accounts, salesOrders, orders, lines, invoices, counterInvoices, counterLines, counter, businesses,
            accountRows, pricing);

    @Test
    void nothingWorksWhileCounterOrdersAreOff() {
        assertThatThrownBy(() -> service.putOnAccount(BUSINESS, "user-1", "inv-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(accounts, salesOrders, counterInvoices, counter);
    }

    @Test
    void aPhoneWithNoApprovedAccountHasNoTerms() {
        when(flags.isEnabled(BUSINESS, FeatureFlagService.FLAG_SUPPLIER_COUNTER_ORDERS)).thenReturn(true);
        when(accounts.approvedFor(BUSINESS, "0712345678")).thenReturn(Optional.empty());

        assertThat(service.terms(BUSINESS, "0712345678").found()).isFalse();
    }
}
