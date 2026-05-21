package zelisline.ub.storefront.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.storefront.WebOrderFulfillmentStatuses;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Service
@RequiredArgsConstructor
public class WebOrderFulfillmentService {

    private final WebOrderRepository webOrderRepository;
    private final WebOrderAdminService webOrderAdminService;
    private final NotificationOutboxService notificationOutboxService;

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
        if (!WebOrderStatuses.PAID.equals(order.getStatus())) {
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
        order.setFulfillmentStatus(normalized);
        webOrderRepository.save(order);
        enqueueNotification(order, normalized);
        return webOrderAdminService.getOrder(businessId, orderId);
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
        if (WebOrderStatuses.PAID.equals(order.getStatus())) {
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
