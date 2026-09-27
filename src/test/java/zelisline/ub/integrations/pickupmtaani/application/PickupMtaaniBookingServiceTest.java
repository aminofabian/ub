package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService.PickupMtaaniResolved;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniApiException;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.CreatedPackage;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.PackageView;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

class PickupMtaaniBookingServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String KEY = "pm-key";
    private static final long ORIGIN = 362L;
    private static final long DEST = 454L;

    private final PickupMtaaniClient client = mock(PickupMtaaniClient.class);
    private final PickupMtaaniSettingsService settingsService = mock(PickupMtaaniSettingsService.class);
    private final WebOrderRepository webOrderRepository = mock(WebOrderRepository.class);
    private final WebOrderLineRepository webOrderLineRepository = mock(WebOrderLineRepository.class);
    private final WebOrderShipmentRepository shipmentRepository = mock(WebOrderShipmentRepository.class);

    private final PickupMtaaniBookingService service = new PickupMtaaniBookingService(
            client, settingsService, webOrderRepository, webOrderLineRepository, shipmentRepository);

    private WebOrder order;
    private WebOrderShipment shipment;

    @BeforeEach
    void setUp() {
        order = new WebOrder();
        order.setId(ORDER_ID);
        order.setBusinessId(BUSINESS_ID);
        order.setStatus(WebOrderStatuses.PAID);
        order.setCustomerName("Ada");
        order.setCustomerPhone("0700456789");
        order.setGrandTotal(new BigDecimal("180.00"));

        shipment = new WebOrderShipment();
        shipment.setId("ship-1");
        shipment.setBusinessId(BUSINESS_ID);
        shipment.setWebOrderId(ORDER_ID);
        shipment.setCarrier(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        shipment.setMode("agent");
        shipment.setOriginAgentId(ORIGIN);
        shipment.setDestinationAgentId(DEST);
        shipment.setDestinationLabel("Westlands Agent");
        shipment.setQuotedFeeKes(new BigDecimal("150.00"));
        shipment.setShopperFeeKes(new BigDecimal("150.00"));
        shipment.setFeeMode("pass_through");
        shipment.setBookStatus(WebOrderShipment.BOOK_PENDING);

        WebOrderLine line = new WebOrderLine();
        when(webOrderRepository.findByIdAndBusinessId(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(order));
        when(webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(ORDER_ID)).thenReturn(List.of(line, line));
        when(shipmentRepository.findByWebOrderIdAndBusinessId(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(WebOrderShipment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(new PickupMtaaniResolved(
                KEY, true, "single_business", ORIGIN, "Kahawa West", "Kahawa",
                true, true, "pass_through", 0, true));
    }

    @Test
    void book_requiresPaidOrder() {
        order.setStatus(WebOrderStatuses.PENDING_PAYMENT);

        assertThatThrownBy(() -> service.book(BUSINESS_ID, ORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("after the order is paid");
    }

    @Test
    void book_withoutShipment_conflict() {
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(BUSINESS_ID, ORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no Pickup Mtaani delivery");
    }

    @Test
    void book_alreadyBooked_isIdempotent() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);

        service.book(BUSINESS_ID, ORDER_ID);

        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void book_alreadyBooking_isIdempotent() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKING);

        service.book(BUSINESS_ID, ORDER_ID);

        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void book_locksShipmentRowBeforeDeciding() {
        when(client.createAgentPackage(any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new CreatedPackage(99L, "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", "{}"));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", null, null, null, null, "Parcel created", "{}"));

        service.book(BUSINESS_ID, ORDER_ID);

        verify(shipmentRepository).findForUpdate(ORDER_ID, BUSINESS_ID);
    }

    @Test
    void cancel_locksShipmentRowBeforeDeciding() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(99L);
        shipment.setUpstreamState("request");

        service.cancel(BUSINESS_ID, ORDER_ID);

        verify(shipmentRepository).findForUpdate(ORDER_ID, BUSINESS_ID);
    }

    @Test
    void book_agent_happyPath_createsReadsBackAndMarksBooked() {
        String code = WebOrderCodes.code(ORDER_ID);
        when(client.createAgentPackage(eq(KEY), eq(ORIGIN), eq(DEST), eq("Ada"), eq("+254700456789"),
                contains(code), eq(30)))
                .thenReturn(new CreatedPackage(99L, "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", "{}"));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", null, "Parcel created", "{}"));

        service.book(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_BOOKED);
        assertThat(shipment.getUpstreamPackageId()).isEqualTo(99L);
        assertThat(shipment.getReceiptNo()).isEqualTo("PMT-PHL-1");
        assertThat(shipment.getPaymentStatus()).isEqualTo("Not Paid");
        assertThat(shipment.getUpstreamState()).isEqualTo("request");
        assertThat(shipment.getLastTrackDescription()).isEqualTo("Parcel created");
        assertThat(shipment.getBookedAt()).isNotNull();
    }

    @Test
    void book_createFailure_marksBookFailed() {
        when(client.createAgentPackage(any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new PickupMtaaniApiException(400, "AGENTS.DELIVERY.DESTINATION_HIDDEN", null,
                        "Destination agent is hidden"));

        service.book(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_FAILED);
        assertThat(shipment.getBookError()).isEqualTo("Destination agent is hidden");
        assertThat(shipment.getUpstreamPackageId()).isNull();
    }

    @Test
    void book_invalidPhone_marksBookFailedWithoutCalling() {
        order.setCustomerPhone("0201234567");

        service.book(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_FAILED);
        assertThat(shipment.getBookError()).contains("valid Kenyan mobile");
        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void book_getBackFailure_keepsBooked() {
        when(client.createAgentPackage(any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new CreatedPackage(99L, "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", "{}"));
        when(client.getAgentPackage(KEY, 99L)).thenThrow(new PickupMtaaniApiException(null, null, null, "network"));

        service.book(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_BOOKED);
        assertThat(shipment.getUpstreamPackageId()).isEqualTo(99L);
    }

    @Test
    void bookOnDispatch_disabled_noCreate() {
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(new PickupMtaaniResolved(
                KEY, true, "single_business", ORIGIN, null, null, true, true, "pass_through", 0, false));

        service.bookOnDispatchIfEnabled(BUSINESS_ID, ORDER_ID);

        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void bookOnDispatch_enabled_booksPendingShipment() {
        when(client.createAgentPackage(any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new CreatedPackage(99L, "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", "{}"));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", null, null, null, null, "Parcel created", "{}"));

        service.bookOnDispatchIfEnabled(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_BOOKED);
        assertThat(shipment.getUpstreamPackageId()).isEqualTo(99L);
    }

    @Test
    void bookOnDispatch_alreadyBooked_noCreate() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);

        service.bookOnDispatchIfEnabled(BUSINESS_ID, ORDER_ID);

        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void cancel_notBooked_standsDownLocallyWithoutUpstreamCall() {
        shipment.setBookStatus(WebOrderShipment.BOOK_PENDING);

        service.cancel(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_CANCELLED);
        verify(client, never()).cancelAgentPackage(any(), any());
    }

    @Test
    void cancel_requestState_deletesUpstream() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(99L);
        shipment.setUpstreamState("request");

        service.cancel(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_CANCELLED);
        verify(client).cancelAgentPackage(KEY, 99L);
    }

    @Test
    void cancel_voidedState_deletesUpstream() {
        shipment.setBookStatus(WebOrderShipment.BOOK_VOIDED);
        shipment.setUpstreamPackageId(99L);
        shipment.setUpstreamState("request");

        service.cancel(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_CANCELLED);
        verify(client).cancelAgentPackage(KEY, 99L);
    }

    @Test
    void book_voidedState_isIdempotent() {
        shipment.setBookStatus(WebOrderShipment.BOOK_VOIDED);

        service.book(BUSINESS_ID, ORDER_ID);

        verify(client, never()).createAgentPackage(any(), anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void cancel_alreadyMoved_conflictWithoutCalling() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(99L);
        shipment.setUpstreamState("in_transit");

        assertThatThrownBy(() -> service.cancel(BUSINESS_ID, ORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already moved");
        verify(client, never()).cancelAgentPackage(any(), any());
    }

    @Test
    void cancel_bookedWithoutUpstreamId_conflict() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(null);

        assertThatThrownBy(() -> service.cancel(BUSINESS_ID, ORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no upstream id");
    }

    @Test
    void cancel_upstreamFailure_restoresBooked() {
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(99L);
        shipment.setUpstreamState("request");
        org.mockito.Mockito.doThrow(new PickupMtaaniApiException(400, null, null, "Cannot delete"))
                .when(client).cancelAgentPackage(KEY, 99L);

        service.cancel(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_BOOKED);
        assertThat(shipment.getBookError()).isEqualTo("Cannot delete");
    }
}
