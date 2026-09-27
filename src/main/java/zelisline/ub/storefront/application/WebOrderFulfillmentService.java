package zelisline.ub.storefront.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniBookingService;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.storefront.WebOrderChannels;
import zelisline.ub.storefront.WebOrderFulfillmentStatuses;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;

@Service
@RequiredArgsConstructor
public class WebOrderFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(WebOrderFulfillmentService.class);

    private final WebOrderRepository webOrderRepository;
    private final WebOrderAdminService webOrderAdminService;
    private final NotificationOutboxService notificationOutboxService;
    private final WhatsAppOrderExpiryService expiryService;
    private final PickupMtaaniBookingService pickupMtaaniBookingService;
    private final WebOrderShipmentRepository webOrderShipmentRepository;

    @Value("${app.storefront.web-orders.auto-confirm-on-paid:false}")
    private boolean autoConfirmOnPaid;

    @Transactional
    public void onOrderPaid(WebOrder order) {
        if (!WebOrderStatuses.PAID.equals(order.getStatus())) {
            return;
        }
        if (order.getFulfillmentStatus() == null || order.getFulfillmentStatus().isBlank()) {
            order.setFulfillmentStatus(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);
            webOrderRepository.save(order);
        }
        if (autoConfirmOnPaid) {
            advance(order.getBusinessId(), order.getId(), WebOrderFulfillmentStatuses.CONFIRMED);
        }
    }

    @Transactional
    public WebOrderDetailResponse advance(String businessId, String orderId, String targetStatus) {
        String normalized = normalizeTarget(targetStatus);
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!WebOrderStatuses.PAID.equals(order.getStatus())
                && !WebOrderChannels.WHATSAPP.equals(order.getChannel())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Fulfillment updates require a paid order");
        }
        String current = effectiveFulfillment(order);
        if (!isAllowedTransition(current, normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot move fulfillment from " + current + " to " + normalized);
        }
        if (current.equals(normalized)) {
            return webOrderAdminService.getOrder(businessId, orderId);
        }
        // WhatsApp settlement is arranged in chat: confirming an order the expiry
        // sweeper already released re-decrements stock (scope §11, soft expiry).
        if (WebOrderFulfillmentStatuses.CONFIRMED.equals(normalized)) {
            expiryService.reReserveStock(order);
        }
        order.setFulfillmentStatus(normalized);
        webOrderRepository.save(order);
        enqueueNotification(order, normalized);
        if (WebOrderFulfillmentStatuses.DISPATCHED.equals(normalized)) {
            schedulePickupBooking(order.getBusinessId(), order.getId());
        }
        return webOrderAdminService.getOrder(businessId, orderId);
    }

    /**
     * Book-on-dispatch (scope §4 #6, §8): enqueue the Pickup Mtaani create after
     * the fulfilment save commits, so a carrier failure never rolls fulfilment
     * back. The booking service no-ops when the tenant has auto-book off.
     */
    private void schedulePickupBooking(String businessId, String orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeBook(businessId, orderId);
                }
            });
            return;
        }
        safeBook(businessId, orderId);
    }

    private void safeBook(String businessId, String orderId) {
        try {
            pickupMtaaniBookingService.bookOnDispatchIfEnabled(businessId, orderId);
        } catch (RuntimeException ex) {
            log.warn("[pickup-mtaani] book-on-dispatch failed for order {}: {}", orderId, ex.getMessage());
        }
    }

    /**
     * Merchant voids an order that should not be fulfilled. Stock reserved at
     * checkout is put back; completed pickups stay as they are.
     */
    @Transactional
    public WebOrderDetailResponse voidOrder(String businessId, String orderId) {
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (WebOrderStatuses.CANCELLED.equals(order.getStatus())) {
            return webOrderAdminService.getOrder(businessId, orderId);
        }
        String fulfillment = effectiveFulfillment(order);
        if (WebOrderFulfillmentStatuses.COMPLETED.equals(fulfillment)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A completed pickup cannot be voided");
        }
        expiryService.releaseStockForVoid(order);
        order.setStatus(WebOrderStatuses.CANCELLED);
        order.setFulfillmentStatus(WebOrderStatuses.CANCELLED);
        webOrderRepository.save(order);
        standDownPickupShipment(businessId, orderId);
        return webOrderAdminService.getOrder(businessId, orderId);
    }

    /**
     * A voided order must not leave a live parcel polling (scope §13). A shipment
     * still live upstream becomes {@code voided}: polling stops and the merchant
     * cancels it with the existing Cancel booking action, or inside Pickup Mtaani.
     * A shipment with nothing upstream just stands down locally.
     */
    private void standDownPickupShipment(String businessId, String orderId) {
        webOrderShipmentRepository.findForUpdate(orderId, businessId).ifPresent(shipment -> {
            switch (shipment.getBookStatus()) {
                case WebOrderShipment.BOOK_BOOKED -> {
                    shipment.setBookStatus(WebOrderShipment.BOOK_VOIDED);
                    shipment.setBookError(
                            "The order was voided. Cancel this parcel in Pickup Mtaani if it is still live.");
                    webOrderShipmentRepository.save(shipment);
                }
                case WebOrderShipment.BOOK_PENDING,
                     WebOrderShipment.BOOK_BOOKING,
                     WebOrderShipment.BOOK_FAILED -> {
                    shipment.setBookStatus(WebOrderShipment.BOOK_CANCELLED);
                    shipment.setBookError(null);
                    webOrderShipmentRepository.save(shipment);
                }
                default -> {
                    // Already cancelled or voided, or a cancel is in flight.
                }
            }
        });
    }

    private void enqueueNotification(WebOrder order, String fulfillmentStatus) {
        switch (fulfillmentStatus) {
            case WebOrderFulfillmentStatuses.CONFIRMED ->
                    notificationOutboxService.enqueueWebOrderConfirmed(order);
            case WebOrderFulfillmentStatuses.DISPATCHED ->
                    notificationOutboxService.enqueueWebOrderDispatched(order);
            case WebOrderFulfillmentStatuses.COMPLETED ->
                    notificationOutboxService.enqueueWebOrderDelivered(order);
            default -> {
            }
        }
    }

    private static String effectiveFulfillment(WebOrder order) {
        if (order.getFulfillmentStatus() != null && !order.getFulfillmentStatus().isBlank()) {
            return order.getFulfillmentStatus().trim();
        }
        if (WebOrderStatuses.PAID.equals(order.getStatus())
                || WebOrderChannels.WHATSAPP.equals(order.getChannel())) {
            return WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION;
        }
        return "";
    }

    private static boolean isAllowedTransition(String current, String target) {
        return switch (current) {
            case WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION ->
                    WebOrderFulfillmentStatuses.CONFIRMED.equals(target);
            case WebOrderFulfillmentStatuses.CONFIRMED ->
                    WebOrderFulfillmentStatuses.DISPATCHED.equals(target);
            case WebOrderFulfillmentStatuses.DISPATCHED ->
                    WebOrderFulfillmentStatuses.COMPLETED.equals(target);
            default -> false;
        };
    }

    private static String normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fulfillmentStatus required");
        }
        String s = raw.trim().toLowerCase();
        if (!WebOrderFulfillmentStatuses.CONFIRMED.equals(s)
                && !WebOrderFulfillmentStatuses.DISPATCHED.equals(s)
                && !WebOrderFulfillmentStatuses.COMPLETED.equals(s)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fulfillmentStatus must be confirmed, dispatched, or completed");
        }
        return s;
    }
}
