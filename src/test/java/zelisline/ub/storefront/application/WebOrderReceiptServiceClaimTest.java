package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.storefront.api.dto.WebOrderPickupTicketClaimResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.application.BranchReceiptSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class WebOrderReceiptServiceClaimTest {

    @Mock WebOrderRepository webOrderRepository;
    @Mock WebOrderLineRepository webOrderLineRepository;
    @Mock BusinessRepository businessRepository;
    @Mock BranchRepository branchRepository;
    @Mock ItemRepository itemRepository;
    @Mock BranchReceiptSettingsService branchReceiptSettingsService;
    @Mock StorefrontSettingsService storefrontSettingsService;

    WebOrderReceiptService service;

    @BeforeEach
    void setUp() {
        service = new WebOrderReceiptService(
                webOrderRepository,
                webOrderLineRepository,
                businessRepository,
                branchRepository,
                itemRepository,
                branchReceiptSettingsService,
                storefrontSettingsService);
    }

    @Test
    void claimRejectsAlreadyPrinted() {
        WebOrder order = freshOrder(Instant.now().minusSeconds(60));
        order.setPickupTicketPrintedAt(Instant.now().minusSeconds(10));
        when(webOrderRepository.findByIdAndBusinessId("o1", "b1")).thenReturn(Optional.of(order));

        WebOrderPickupTicketClaimResponse result = service.claimPickupTicketPrint("b1", "o1");

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).isEqualTo("already_printed");
        verify(webOrderRepository, never()).claimPickupTicketPrint(any(), any(), any(), any());
    }

    @Test
    void claimRejectsTooOld() {
        WebOrder order = freshOrder(Instant.now().minusSeconds(2 * 60 * 60));
        when(webOrderRepository.findByIdAndBusinessId("o1", "b1")).thenReturn(Optional.of(order));

        WebOrderPickupTicketClaimResponse result = service.claimPickupTicketPrint("b1", "o1");

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).isEqualTo("too_old");
        verify(webOrderRepository, never()).claimPickupTicketPrint(any(), any(), any(), any());
    }

    @Test
    void claimSucceedsForRecentUnprinted() {
        WebOrder order = freshOrder(Instant.now().minusSeconds(120));
        when(webOrderRepository.findByIdAndBusinessId("o1", "b1")).thenReturn(Optional.of(order));
        when(webOrderRepository.claimPickupTicketPrint(eq("o1"), eq("b1"), any(), any())).thenReturn(1);

        WebOrderPickupTicketClaimResponse result = service.claimPickupTicketPrint("b1", "o1");

        assertThat(result.claimed()).isTrue();
        assertThat(result.reason()).isEqualTo("claimed");
    }

    private static WebOrder freshOrder(Instant createdAt) {
        WebOrder order = new WebOrder();
        order.setId("o1");
        order.setBusinessId("b1");
        order.setCartId("c1");
        order.setCatalogBranchId("br1");
        order.setStatus("pending_payment");
        order.setCurrency("KES");
        order.setGrandTotal(BigDecimal.TEN);
        order.setCustomerName("Ada");
        order.setCustomerPhone("0700");
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        return order;
    }
}
