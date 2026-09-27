package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService.PickupMtaaniResolved;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.PackageView;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

class PickupMtaaniPollerTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String ORDER_ID = "order-1";
    private static final String KEY = "pm-key";

    private final PickupMtaaniClient client = mock(PickupMtaaniClient.class);
    private final PickupMtaaniSettingsService settingsService = mock(PickupMtaaniSettingsService.class);
    private final WebOrderShipmentRepository shipmentRepository = mock(WebOrderShipmentRepository.class);

    private final PickupMtaaniPoller poller =
            new PickupMtaaniPoller(client, settingsService, shipmentRepository);

    private WebOrderShipment shipment;

    @BeforeEach
    void setUp() {
        shipment = new WebOrderShipment();
        shipment.setId("ship-1");
        shipment.setBusinessId(BUSINESS_ID);
        shipment.setWebOrderId(ORDER_ID);
        shipment.setCarrier(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        shipment.setMode("agent");
        shipment.setOriginAgentId(362L);
        shipment.setDestinationAgentId(454L);
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setUpstreamPackageId(99L);
        shipment.setBookedAt(Instant.now());

        when(settingsService.resolve(BUSINESS_ID)).thenReturn(new PickupMtaaniResolved(
                KEY, true, "single_business", 362L, null, null, true, true, "pass_through", 0, true));
        when(shipmentRepository.save(any(WebOrderShipment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void refresh_withoutUpstreamId_conflict() {
        shipment.setUpstreamPackageId(null);
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> poller.refresh(BUSINESS_ID, ORDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not booked yet");
    }

    @Test
    void refresh_mapsStateAndTrack() {
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "in_transit", "PUM-AG-PHL-1", "PMT-PHL-1", "Not Paid", null, "Left the hub", "{}"));

        poller.refresh(BUSINESS_ID, ORDER_ID);

        assertThat(shipment.getUpstreamState()).isEqualTo("in_transit");
        assertThat(shipment.getLastTrackDescription()).isEqualTo("Left the hub");
        assertThat(shipment.getLastPolledAt()).isNotNull();
        verify(shipmentRepository).save(shipment);
    }

    @Test
    void refresh_notReady_skipsUpstream() {
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(shipment));
        when(settingsService.resolve(BUSINESS_ID)).thenReturn(PickupMtaaniResolved.disabled());

        poller.refresh(BUSINESS_ID, ORDER_ID);

        verify(client, never()).getAgentPackage(any(), anyLong());
    }

    @Test
    void pollDue_refreshesBookedShipments() {
        when(shipmentRepository.findTop200ByBookStatusAndBookedAtAfter(
                eq(WebOrderShipment.BOOK_BOOKED), any(Instant.class)))
                .thenReturn(List.of(shipment));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", null, null, null, null, "Parcel created", "{}"));

        poller.pollDue();

        assertThat(shipment.getUpstreamState()).isEqualTo("request");
        verify(shipmentRepository).save(shipment);
    }

    @Test
    void pollDue_swallowsPerShipmentFailure() {
        when(shipmentRepository.findTop200ByBookStatusAndBookedAtAfter(
                eq(WebOrderShipment.BOOK_BOOKED), any(Instant.class)))
                .thenReturn(List.of(shipment));
        when(client.getAgentPackage(KEY, 99L)).thenThrow(new RuntimeException("boom"));

        // Must not propagate; the loop continues.
        poller.pollDue();
    }

    @Test
    void refresh_locksShipmentRowBeforeDeciding() {
        when(shipmentRepository.findForUpdate(ORDER_ID, BUSINESS_ID)).thenReturn(Optional.of(shipment));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", null, null, null, null, "Parcel created", "{}"));

        poller.refresh(BUSINESS_ID, ORDER_ID);

        verify(shipmentRepository).findForUpdate(ORDER_ID, BUSINESS_ID);
    }

    @Test
    void pollDue_skipsShipmentStaledByAConcurrentCancel() {
        when(shipmentRepository.findTop200ByBookStatusAndBookedAtAfter(
                eq(WebOrderShipment.BOOK_BOOKED), any(Instant.class)))
                .thenReturn(List.of(shipment));
        when(client.getAgentPackage(KEY, 99L)).thenReturn(new PackageView(
                "request", null, null, null, null, "Parcel created", "{}"));
        // A concurrent cancel bumped the version between the list read and this save.
        org.mockito.Mockito.doThrow(new jakarta.persistence.OptimisticLockException("stale version"))
                .when(shipmentRepository).save(shipment);

        // Must not propagate, and must not overwrite the cancel.
        poller.pollDue();
    }
}
