package zelisline.ub.integrations.pickupmtaani.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService.PickupMtaaniResolved;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.PackageView;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

/**
 * Mirrors Pickup Mtaani package state back onto shipments (scope §11, §12).
 * Polling is the source of truth; the webhook is not trusted in V1.
 *
 * <p>Runs every ten minutes for booked, non-terminal packages inside a 14-day
 * window, and on the merchant's explicit Refresh. No upstream state is mapped to
 * a Palmart fulfilment move until the spike records the real state enum.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniPoller {

    private static final Logger log = LoggerFactory.getLogger(PickupMtaaniPoller.class);

    private final PickupMtaaniClient pickupMtaaniClient;
    private final PickupMtaaniSettingsService settingsService;
    private final WebOrderShipmentRepository shipmentRepository;

    @Value("${app.pickup-mtaani.poll-max-age-days:14}")
    private int maxAgeDays;

    @Scheduled(
            fixedDelayString = "${app.pickup-mtaani.poll-interval-ms:600000}",
            initialDelayString = "${app.pickup-mtaani.poll-initial-delay-ms:60000}")
    public void pollDue() {
        Instant cutoff = Instant.now().minus(Math.max(1, maxAgeDays), ChronoUnit.DAYS);
        List<WebOrderShipment> due = shipmentRepository
                .findTop200ByBookStatusAndBookedAtAfter(WebOrderShipment.BOOK_BOOKED, cutoff);
        for (WebOrderShipment shipment : due) {
            try {
                refreshOne(shipment);
            } catch (RuntimeException ex) {
                log.warn("[pickup-mtaani] poll failed for shipment {}: {}", shipment.getId(), ex.getMessage());
            }
        }
    }

    /** Merchant "Refresh status" for one order. */
    @Transactional
    public void refresh(String businessId, String orderId) {
        // Lock the row so a merchant refresh cannot interleave with a cancel or a
        // second booking (scope §13). The scheduled poller below stays unlocked and
        // relies on the optimistic version instead.
        WebOrderShipment shipment = shipmentRepository
                .findForUpdate(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order has no shipment."));
        if (shipment.getUpstreamPackageId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This parcel is not booked yet.");
        }
        refreshOne(shipment);
    }

    private void refreshOne(WebOrderShipment shipment) {
        if (!WebOrderShipment.BOOK_BOOKED.equals(shipment.getBookStatus())
                || shipment.getUpstreamPackageId() == null) {
            return;
        }
        PickupMtaaniResolved config = settingsService.resolve(shipment.getBusinessId());
        if (!config.ready()) {
            return;
        }
        PackageView view = PickupMtaaniCheckoutService.MODE_AGENT.equals(shipment.getMode())
                ? pickupMtaaniClient.getAgentPackage(config.apiKey(), shipment.getUpstreamPackageId())
                : pickupMtaaniClient.getDoorstepPackage(config.apiKey(), shipment.getUpstreamPackageId());
        applyView(shipment, view);
        shipmentRepository.save(shipment);
    }

    private static void applyView(WebOrderShipment shipment, PackageView view) {
        shipment.setUpstreamState(view.state());
        shipment.setLastTrackDescription(view.lastTrackDescription());
        if (view.trackId() != null) {
            shipment.setTrackId(view.trackId());
        }
        if (view.receiptNo() != null) {
            shipment.setReceiptNo(view.receiptNo());
        }
        if (view.paymentStatus() != null) {
            shipment.setPaymentStatus(view.paymentStatus());
        }
        shipment.setRawLastPayload(view.rawJson());
        shipment.setLastPolledAt(Instant.now());
        // §11: no upstream state moves Palmart fulfilment until the spike records
        // the enum. Unknown states update the row and leave fulfilment untouched.
    }
}
