package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniBookingService;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.storefront.WebOrderFulfillmentStatuses;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

class WebOrderFulfillmentServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String ORDER_ID = "order-1";

    private final WebOrderRepository webOrderRepository = mock(WebOrderRepository.class);
    private final WebOrderAdminService webOrderAdminService = mock(WebOrderAdminService.class);
    private final NotificationOutboxService notificationOutboxService = mock(NotificationOutboxService.class);
    private final WhatsAppOrderExpiryService expiryService = mock(WhatsAppOrderExpiryService.class);
    private final PickupMtaaniBookingService pickupMtaaniBookingService = mock(PickupMtaaniBookingService.class);
    private final WebOrderShipmentRepository webOrderShipmentRepository = mock(WebOrderShipmentRepository.class);

    private final WebOrderFulfillmentService service = new WebOrderFulfillmentService(
            webOrderRepository, webOrderAdminService, notificationOutboxService,
            expiryService, pickupMtaaniBookingService, webOrderShipmentRepository);

    private WebOrder order;

    @BeforeEach
    void setUp() {
        order = new WebOrder();
        order.setId(ORDER_ID);
        order.setBusinessId(BUSINESS_ID);
        order.setStatus(WebOrderStatuses.PAID);
        when(webOrderRepository.findByIdAndBusinessId(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(order));
        when(webOrderRepository.save(any(WebOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void advance_toDispatched_triggersPickupBooking() {
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.CONFIRMED);

        service.advance(BUSINESS_ID, ORDER_ID, WebOrderFulfillmentStatuses.DISPATCHED);

        verify(pickupMtaaniBookingService).bookOnDispatchIfEnabled(BUSINESS_ID, ORDER_ID);
    }

    @Test
    void advance_toConfirmed_doesNotBook() {
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);

        service.advance(BUSINESS_ID, ORDER_ID, WebOrderFulfillmentStatuses.CONFIRMED);

        verify(pickupMtaaniBookingService, never()).bookOnDispatchIfEnabled(any(), any());
    }

    @Test
    void voidOrder_bookedShipment_marksVoidedAndStopsPolling() {
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.DISPATCHED);
        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        when(webOrderShipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));

        service.voidOrder(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_VOIDED);
        assertThat(shipment.getBookError()).contains("voided");
        verify(webOrderShipmentRepository).save(shipment);
    }

    @Test
    void voidOrder_pendingShipment_standsDownLocally() {
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.CONFIRMED);
        WebOrderShipment shipment = new WebOrderShipment();
        shipment.setBookStatus(WebOrderShipment.BOOK_PENDING);
        when(webOrderShipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));

        service.voidOrder(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_CANCELLED);
        assertThat(shipment.getBookError()).isNull();
    }

    @Test
    void voidOrder_withoutShipment_isFine() {
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.CONFIRMED);
        when(webOrderShipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.empty());

        service.voidOrder(BUSINESS_ID, ORDER_ID);

        verify(webOrderShipmentRepository, never()).save(any(WebOrderShipment.class));
    }
}
