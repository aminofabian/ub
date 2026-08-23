package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.storefront.api.dto.PublicOrderTrackingResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

class PublicWebOrderTrackingServiceTest {

    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CODE = "55440000"; // last 8 hex of ORDER_ID

    private BusinessRepository businessRepository;
    private WebOrderRepository webOrderRepository;
    private BranchRepository branchRepository;
    private ReceiptTokenService receiptTokenService;
    private PublicWebOrderTrackingService service;

    @BeforeEach
    void setUp() {
        businessRepository = Mockito.mock(BusinessRepository.class);
        webOrderRepository = Mockito.mock(WebOrderRepository.class);
        branchRepository = Mockito.mock(BranchRepository.class);
        receiptTokenService = Mockito.mock(ReceiptTokenService.class);
        service = new PublicWebOrderTrackingService(
                businessRepository, webOrderRepository, branchRepository, receiptTokenService);
    }

    @Test
    void trackByTokenReturnsVerifiedSnapshotWithPhone() {
        stubOrderLookup();
        Branch branch = branch();
        Mockito.when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("branch-1", "b1"))
                .thenReturn(Optional.of(branch));
        Mockito.when(receiptTokenService.verifyAndConsume(Mockito.any(), Mockito.eq("TOKEN123")))
                .thenReturn(true);

        PublicOrderTrackingResponse response = service.trackByToken("acme", CODE, "TOKEN123");

        assertThat(response.receiptVerified()).isTrue();
        assertThat(response.customerPhone()).isEqualTo("0714 282 874");
        assertThat(response.orderCode()).isEqualTo(CODE);
        assertThat(response.catalogBranchName()).isEqualTo("Downtown");
    }

    @Test
    void trackByTokenRejectsBadTokenWithGenericNotFound() {
        stubOrderLookup();
        Mockito.when(receiptTokenService.verifyAndConsume(Mockito.any(), Mockito.eq("WRONG")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.trackByToken("acme", CODE, "WRONG"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void trackByCodeStillGatesOnPhoneLast4() {
        stubOrderLookup();
        Branch branch = branch();
        Mockito.when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("branch-1", "b1"))
                .thenReturn(Optional.of(branch));

        PublicOrderTrackingResponse response = service.trackByCode("acme", CODE, "2874");

        assertThat(response.receiptVerified()).isNull();
        assertThat(response.customerPhone()).isNull();
        assertThat(response.orderCode()).isEqualTo(CODE);
    }

    private void stubOrderLookup() {
        Business business = new Business();
        business.setId("b1");
        business.setSlug("acme");
        Mockito.when(businessRepository.findBySlugAndDeletedAtIsNull("acme"))
                .thenReturn(Optional.of(business));

        WebOrder order = new WebOrder();
        order.setId(ORDER_ID);
        order.setBusinessId("b1");
        order.setCatalogBranchId("branch-1");
        order.setCustomerPhone("0714 282 874");
        order.setGrandTotal(new BigDecimal("1250.00"));
        order.setCurrency("KES");
        Mockito.when(webOrderRepository.findByBusinessIdOrderByCreatedAtDesc(
                        Mockito.eq("b1"), Mockito.any()))
                .thenReturn(new PageImpl<>(List.of(order)));
    }

    private static Branch branch() {
        Branch branch = new Branch();
        branch.setId("branch-1");
        branch.setName("Downtown");
        return branch;
    }
}
