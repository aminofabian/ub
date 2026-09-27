package zelisline.ub.integrations.pickupmtaani.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
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

/**
 * Manual "Book pickup" for a paid web order (scope §8, build step 5). Creates the
 * upstream parcel, then reads it back for the first state/track. No booking on
 * dispatch, refresh, or cancel here — those are later steps.
 *
 * <p>Idempotency follows §13: mark {@code booking} before the call, persist a
 * returned id immediately, and never blindly re-create. A failure leaves
 * {@code book_failed} with a safe message for the merchant to retry.
 */
@Service
@RequiredArgsConstructor
public class PickupMtaaniBookingService {

    private static final String MODE_AGENT = PickupMtaaniCheckoutService.MODE_AGENT;
    private static final int MAX_PACKAGE_NAME = 100;
    private static final int MAX_ERROR = 1000;

    private final PickupMtaaniClient pickupMtaaniClient;
    private final PickupMtaaniSettingsService settingsService;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final WebOrderShipmentRepository shipmentRepository;

    @Transactional
    public void book(String businessId, String orderId) {
        WebOrder order = webOrderRepository.findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!WebOrderStatuses.PAID.equals(order.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Book pickup after the order is paid.");
        }
        // Row lock (scope §13): the decision to create must serialise, or two
        // concurrent calls both read `pending` and both create a parcel.
        WebOrderShipment shipment = shipmentRepository
                .findForUpdate(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This order has no Pickup Mtaani delivery."));
        String status = shipment.getBookStatus();
        if (WebOrderShipment.BOOK_BOOKED.equals(status)
                || WebOrderShipment.BOOK_BOOKING.equals(status)
                || WebOrderShipment.BOOK_CANCELLED.equals(status)
                || WebOrderShipment.BOOK_VOIDED.equals(status)) {
            return;
        }

        PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pickup Mtaani is not available for this shop.");
        }

        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKING);
        shipment.setBookError(null);
        shipmentRepository.save(shipment);

        String phone = PickupMtaaniPhone.toApiFormat(order.getCustomerPhone());
        if (phone == null) {
            fail(shipment, "The shopper's phone is not a valid Kenyan mobile for Pickup Mtaani.");
            return;
        }
        String packageName = packageName(order);
        Integer packageValue = shipment.getPackageValueKes() != null
                ? shipment.getPackageValueKes()
                : goodsValue(order, shipment);

        try {
            CreatedPackage created = MODE_AGENT.equals(shipment.getMode())
                    ? pickupMtaaniClient.createAgentPackage(
                            config.apiKey(), config.originAgentId(), shipment.getDestinationAgentId(),
                            order.getCustomerName(), phone, packageName, packageValue)
                    : pickupMtaaniClient.createDoorstepPackage(
                            config.apiKey(), config.originAgentId(), shipment.getDoorstepDestinationId(),
                            order.getCustomerName(), phone, packageName,
                            shipment.getLocationDescription(), packageValue);
            applyCreated(config.apiKey(), shipment, created);
        } catch (PickupMtaaniApiException ex) {
            fail(shipment, ex.getMessage());
        }
    }

    /**
     * Books on dispatch when the tenant opted in (scope §4 #6, §8). Silently
     * no-ops when the option is off, not ready, or the shipment is already settled.
     */
    @Transactional
    public void bookOnDispatchIfEnabled(String businessId, String orderId) {
        PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready() || !config.bookOnDispatch()) {
            return;
        }
        WebOrderShipment shipment = shipmentRepository
                .findByWebOrderIdAndBusinessId(orderId, businessId)
                .orElse(null);
        if (shipment == null) {
            return;
        }
        if (!WebOrderShipment.BOOK_PENDING.equals(shipment.getBookStatus())
                && !WebOrderShipment.BOOK_FAILED.equals(shipment.getBookStatus())) {
            return;
        }
        WebOrder order = webOrderRepository.findByIdAndBusinessId(orderId, businessId).orElse(null);
        if (order == null || !WebOrderStatuses.PAID.equals(order.getStatus())) {
            return;
        }
        book(businessId, orderId);
    }

    /**
     * Cancels a booking while the parcel is still {@code request} (scope §8, §13).
     * Never calls delete without an id. A parcel that has moved must be cancelled
     * inside Pickup Mtaani.
     */
    @Transactional
    public void cancel(String businessId, String orderId) {
        WebOrderShipment shipment = shipmentRepository
                .findForUpdate(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order has no shipment."));
        if (WebOrderShipment.BOOK_CANCELLED.equals(shipment.getBookStatus())) {
            return;
        }
        // Both a live booking and a parcel left behind by a voided order have a
        // real upstream parcel to delete (scope §13).
        boolean liveParcel = WebOrderShipment.BOOK_BOOKED.equals(shipment.getBookStatus())
                || WebOrderShipment.BOOK_VOIDED.equals(shipment.getBookStatus());
        if (!liveParcel) {
            // Nothing upstream yet — just stand the local booking down so it will
            // not be created on dispatch.
            shipment.setBookStatus(WebOrderShipment.BOOK_CANCELLED);
            shipment.setBookError(null);
            shipmentRepository.save(shipment);
            return;
        }
        Long packageId = shipment.getUpstreamPackageId();
        if (packageId == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This parcel has no upstream id. Cancel it in Pickup Mtaani.");
        }
        if (shipment.getUpstreamState() != null && !"request".equals(shipment.getUpstreamState())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This parcel has already moved. Cancel it in Pickup Mtaani.");
        }
        PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pickup Mtaani is not available for this shop.");
        }

        shipment.setBookStatus(WebOrderShipment.BOOK_CANCEL_REQUESTED);
        shipmentRepository.save(shipment);
        try {
            if (MODE_AGENT.equals(shipment.getMode())) {
                pickupMtaaniClient.cancelAgentPackage(config.apiKey(), packageId);
            } else {
                pickupMtaaniClient.cancelDoorstepPackage(config.apiKey(), packageId);
            }
            shipment.setBookStatus(WebOrderShipment.BOOK_CANCELLED);
            shipment.setBookError(null);
            shipmentRepository.save(shipment);
        } catch (PickupMtaaniApiException ex) {
            shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
            shipment.setBookError(truncate(ex.getMessage(), MAX_ERROR));
            shipmentRepository.save(shipment);
        }
    }

    private void applyCreated(String apiKey, WebOrderShipment shipment, CreatedPackage created) {
        shipment.setUpstreamPackageId(created.id());
        shipment.setTrackId(created.trackId());
        shipment.setReceiptNo(created.receiptNo());
        shipment.setPaymentStatus(created.paymentStatus());
        shipment.setBookStatus(WebOrderShipment.BOOK_BOOKED);
        shipment.setBookError(null);
        shipment.setBookedAt(Instant.now());
        shipmentRepository.save(shipment);

        // The create response has no state or delivery fee; a GET does. A failure
        // here must not undo the booking.
        try {
            PackageView view = MODE_AGENT.equals(shipment.getMode())
                    ? pickupMtaaniClient.getAgentPackage(apiKey, created.id())
                    : pickupMtaaniClient.getDoorstepPackage(apiKey, created.id());
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
            shipmentRepository.save(shipment);
        } catch (RuntimeException ex) {
            // Keep booked; refresh/poller fills tracking in later steps.
        }
    }

    private void fail(WebOrderShipment shipment, String message) {
        shipment.setBookStatus(WebOrderShipment.BOOK_FAILED);
        shipment.setBookError(truncate(message, MAX_ERROR));
        shipmentRepository.save(shipment);
    }

    private String packageName(WebOrder order) {
        List<WebOrderLine> lines = webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId());
        int count = lines.size();
        String suffix = count + (count == 1 ? " item" : " items");
        return truncate(WebOrderCodes.code(order.getId()) + " · " + suffix, MAX_PACKAGE_NAME);
    }

    /** Goods total in whole KES, excluding the delivery fee (scope §9). */
    private static Integer goodsValue(WebOrder order, WebOrderShipment shipment) {
        BigDecimal total = order.getGrandTotal() == null ? BigDecimal.ZERO : order.getGrandTotal();
        BigDecimal delivery = shipment.getShopperFeeKes() == null ? BigDecimal.ZERO : shipment.getShopperFeeKes();
        return PickupMtaaniCheckoutService.toWholeKes(total.subtract(delivery));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
