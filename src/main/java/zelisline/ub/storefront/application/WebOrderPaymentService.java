package zelisline.ub.storefront.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.payments.application.DarajaAccountReferences;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkAudience;
import zelisline.ub.payments.application.StkPushRetryHelper;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.api.dto.WebOrderStkPushResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

/**
 * Merchant-recorded settlement for a web order (scope P0 #4).
 *
 * <p>Cash, send-money, and the till are recorded here. An M-Pesa prompt uses
 * the same {@code WEB_ORDER} settlement as the shopper checkout, so a PIN
 * marks this order paid. Packing stays closed until that happens, including
 * for WhatsApp orders.
 *
 * <p>Stock is not touched: it was already decremented at checkout for every
 * channel, and {@link WebOrderFulfillmentService} owns re-reservation.
 */
@Service
@RequiredArgsConstructor
public class WebOrderPaymentService {

    private final WebOrderRepository webOrderRepository;
    private final WebOrderAdminService webOrderAdminService;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final NotificationOutboxService notificationOutboxService;
    private final WebOrderCustomerMessageService webOrderCustomerMessageService;
    private final StkPushRetryHelper stkPushRetryHelper;
    private final GatewayStkPushService gatewayStkPushService;

    @Transactional
    public WebOrderDetailResponse recordManualPayment(String businessId, String orderId) {
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (WebOrderStatuses.PAID.equals(order.getStatus())) {
            return webOrderAdminService.getOrder(businessId, orderId);
        }
        if (WebOrderStatuses.CANCELLED.equals(order.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A cancelled order cannot be marked paid");
        }

        order.setStatus(WebOrderStatuses.PAID);
        order.setPaidAt(Instant.now());
        webOrderRepository.save(order);

        // Reuse the exact paid path. The customer "payment received" message is sent by
        // WebOrderCustomerNoticeListener off this order's paid event, so every paid path
        // (gateway webhook and this one) messages the shopper exactly once.
        webOrderFulfillmentService.onOrderPaid(order);
        notificationOutboxService.enqueueWebOrderPaid(order);

        return webOrderAdminService.getOrder(businessId, orderId);
    }

    /**
     * Not transactional: the STK helper may wait on the gateway for several
     * seconds before the push is registered against this order.
     */
    public WebOrderStkPushResponse requestMerchantStk(String businessId, String orderId) {
        WebOrder order = requirePayable(businessId, orderId);
        String phone = order.getCustomerPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer phone is required for M-Pesa payment");
        }
        String code = WebOrderCodes.code(order.getId());
        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId,
                null,
                phone,
                order.getGrandTotal(),
                code,
                "Web order " + code,
                StkAudience.STOREFRONT);
        if (!outcome.accepted()) {
            String message = outcome.message() != null ? outcome.message() : "Could not send M-Pesa prompt";
            return new WebOrderStkPushResponse(false, null, message);
        }
        GatewayType gatewayType = GatewayType.valueOf(outcome.gatewayType());
        String merchantRef = GatewayType.DARAJA.equals(gatewayType)
                ? DarajaAccountReferences.forWebOrder(order.getId())
                : order.getId();
        gatewayStkPushService.registerPush(
                businessId,
                gatewayType,
                outcome.configId(),
                outcome.checkoutRequestId(),
                merchantRef,
                StkPushContextType.WEB_ORDER,
                order.getId(),
                order.getGrandTotal(),
                phone);
        order.setPaymentCheckoutId(outcome.checkoutRequestId());
        webOrderRepository.save(order);
        webOrderCustomerMessageService.sendStatusNotice(businessId, orderId, "stk_sent", false);
        return new WebOrderStkPushResponse(true, outcome.checkoutRequestId(), outcome.message());
    }

    private WebOrder requirePayable(String businessId, String orderId) {
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (WebOrderStatuses.PAID.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already paid");
        }
        if (WebOrderStatuses.CANCELLED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A cancelled order cannot be paid");
        }
        return order;
    }
}
