package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.application.BranchReceiptSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

class WebOrderReceiptDeliveryLineTest {

    private static final String BUSINESS_ID = "b1";
    private static final String ORDER_ID = "order-1";

    private final WebOrderRepository webOrderRepository = mock(WebOrderRepository.class);
    private final WebOrderLineRepository webOrderLineRepository = mock(WebOrderLineRepository.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final BranchReceiptSettingsService branchReceiptSettingsService = mock(BranchReceiptSettingsService.class);
    private final StorefrontSettingsService storefrontSettingsService = mock(StorefrontSettingsService.class);
    private final WebOrderShipmentRepository webOrderShipmentRepository = mock(WebOrderShipmentRepository.class);

    private final WebOrderReceiptService service = new WebOrderReceiptService(
            webOrderRepository, webOrderLineRepository, businessRepository, branchRepository,
            itemRepository, branchReceiptSettingsService, storefrontSettingsService,
            webOrderShipmentRepository);

    @BeforeEach
    void setUp() {
        WebOrder order = new WebOrder();
        order.setId(ORDER_ID);
        order.setBusinessId(BUSINESS_ID);
        order.setCatalogBranchId("branch-1");
        order.setStatus("paid");
        order.setGrandTotal(new BigDecimal("190.00"));
        order.setCurrency("KES");
        order.setCustomerName("Ada");
        order.setCustomerPhone("0712345678");
        order.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        when(webOrderRepository.findByIdAndBusinessId(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(order));

        Business business = new Business();
        business.setId(BUSINESS_ID);
        business.setName("Shop");
        business.setCurrency("KES");
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(business));

        Branch branch = new Branch();
        branch.setId("branch-1");
        branch.setName("Main");
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("branch-1", BUSINESS_ID))
                .thenReturn(Optional.of(branch));

        WebOrderLine line = new WebOrderLine();
        line.setOrderId(ORDER_ID);
        line.setItemId("item-1");
        line.setItemName("Milk");
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("20.00"));
        line.setLineTotal(new BigDecimal("40.00"));
        line.setLineIndex(0);
        when(webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(ORDER_ID)).thenReturn(List.of(line));

        Item item = new Item();
        item.setId("item-1");
        item.setBusinessId(BUSINESS_ID);
        item.setName("Milk");
        item.setUnitType("each");
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));

        when(branchReceiptSettingsService.read(any())).thenReturn(mock(BranchReceiptSettingsResponse.class));
        when(storefrontSettingsService.readTenantConfig(any(), any()))
                .thenReturn(TenantConfigBundle.defaults("Shop"));
    }

    private static String renderText(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void withCarrierFee_includesDeliveryLine() {
        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setCarrier(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        shipment.setShopperFeeKes(new BigDecimal("150.00"));
        when(webOrderShipmentRepository.findByWebOrderIdAndBusinessId(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));

        String text = renderText(service.buildEscPos(BUSINESS_ID, ORDER_ID, 80));

        assertThat(text).contains("Delivery");
        assertThat(text).contains("150.00");
        // Total already carries the fee (goods 40 + delivery 150).
        assertThat(text).contains("TOTAL 190.00");
    }

    @Test
    void absorbMode_zeroFee_omitsDeliveryLine() {
        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setCarrier(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        shipment.setShopperFeeKes(BigDecimal.ZERO);
        when(webOrderShipmentRepository.findByWebOrderIdAndBusinessId(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));

        String text = renderText(service.buildEscPos(BUSINESS_ID, ORDER_ID, 80));

        assertThat(text).doesNotContain("Delivery");
    }

    @Test
    void withoutShipment_omitsDeliveryLine() {
        when(webOrderShipmentRepository.findByWebOrderIdAndBusinessId(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.empty());

        String text = renderText(service.buildEscPos(BUSINESS_ID, ORDER_ID, 80));

        assertThat(text).doesNotContain("Delivery");
    }
}
