package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.api.dto.WebOrderMessageResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

/**
 * Merchant-initiated customer message for a web order (scope P0 #5/#6).
 *
 * <p>Closes the gap where a merchant could not contact the shopper from the
 * dashboard at all for non-WhatsApp orders. Mirrors the ops-alert send path:
 * WhatsApp first with SMS fallback, using the tenant's own messaging
 * credentials. Nothing about the money boundary changes — this is a message,
 * not a payment.
 */
@Service
@RequiredArgsConstructor
public class WebOrderCustomerMessageService {

    private static final Logger log = LoggerFactory.getLogger(WebOrderCustomerMessageService.class);

    private static final int MAX_TEXT = 1000;

    private final WebOrderRepository webOrderRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    public WebOrderMessageResponse send(
            String businessId,
            String orderId,
            String kind,
            String text
    ) {
        WebOrder order = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        String phone = StkPhoneNormalizer.normalize(order.getCustomerPhone());
        if (phone == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This order has no valid customer phone number");
        }

        String body = buildBody(order, kind, text);

        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(
                businessId, SmsSendReason.NOTIFICATION);
        if (!messaging.secretsReadable()) {
            return new WebOrderMessageResponse(
                    false,
                    null,
                    messaging.secretsReadError() != null && !messaging.secretsReadError().isBlank()
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured()) {
            return new WebOrderMessageResponse(
                    false,
                    null,
                    "Configure WhatsApp or SMS under Business → Customers → messaging first.");
        }

        // SMS-first when configured (free-form WhatsApp alone can silently fail
        // outside Meta's 24h window), otherwise WhatsApp-only.
        CustomerMessageDispatcher.DeliveryResult result = messaging.smsConfigured()
                ? customerMessageDispatcher.deliverBothChannels(messaging, phone, body)
                : customerMessageDispatcher.deliver(messaging, phone, body);

        boolean sent = "sent".equals(result.outcome());
        log.info(
                "web_order_message order={} business={} kind={} channel={} outcome={}",
                orderId, businessId, kind, result.channel(), result.outcome());
        return new WebOrderMessageResponse(sent, result.channel(), result.detail());
    }

    /**
     * Best-effort customer notice after a status change. Never throws: a
     * messaging failure must not roll back payment or fulfilment.
     */
    public void sendStatusNotice(String businessId, String orderId, String notice, boolean doorstep) {
        try {
            deliverNotice(businessId, orderId, notice, doorstep);
        } catch (RuntimeException ex) {
            log.warn("web_order_notice failed order={} notice={}: {}", orderId, notice, ex.getMessage());
        }
    }

    private void deliverNotice(String businessId, String orderId, String notice, boolean doorstep) {
        WebOrder order = webOrderRepository.findByIdAndBusinessId(orderId, businessId).orElse(null);
        if (order == null) {
            return;
        }
        String phone = StkPhoneNormalizer.normalize(order.getCustomerPhone());
        if (phone == null) {
            return;
        }
        String body = noticeBody(order, notice, doorstep);
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(
                businessId, SmsSendReason.NOTIFICATION);
        if (!messaging.secretsReadable()
                || (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured())) {
            return;
        }
        if (messaging.smsConfigured()) {
            customerMessageDispatcher.deliverBothChannels(messaging, phone, body);
            return;
        }
        customerMessageDispatcher.deliver(messaging, phone, body);
    }

    private static String noticeBody(WebOrder order, String notice, boolean doorstep) {
        String name = firstName(order);
        String code = WebOrderCodes.code(order.getId());
        String amount = formatMoney(order.getGrandTotal(), order.getCurrency());
        return switch (notice) {
            case "stk_sent" -> "Hi " + name + ", we sent an M-Pesa prompt for order #" + code
                    + " (" + amount + "). Enter your PIN to pay.";
            case "payment_received" -> "Hi " + name + ", we received payment for order #" + code
                    + " (" + amount + "). We'll pack it next.";
            case "confirmed" -> "Hi " + name + ", order #" + code + " is confirmed. We're packing it now.";
            case "dispatched" -> doorstep
                    ? "Hi " + name + ", order #" + code + " is on the way."
                    : "Hi " + name + ", order #" + code + " is ready for pickup.";
            case "completed" -> doorstep
                    ? "Hi " + name + ", order #" + code + " has been delivered."
                    : "Hi " + name + ", order #" + code + " is complete. Thank you.";
            default -> "Hi " + name + ", there's an update on order #" + code + ".";
        };
    }

    private static String firstName(WebOrder order) {
        if (order.getCustomerName() == null || order.getCustomerName().isBlank()) {
            return "there";
        }
        return order.getCustomerName().trim().split("\\s+")[0];
    }

    private static String buildBody(WebOrder order, String kind, String text) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if ("custom".equals(normalized)) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message text is required");
            }
            return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
        }
        if (!"payment_request".equals(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "kind must be payment_request or custom");
        }
        String name = order.getCustomerName() != null && !order.getCustomerName().isBlank()
                ? order.getCustomerName().trim().split("\\s+")[0]
                : "there";
        String code = WebOrderCodes.code(order.getId());
        String amount = formatMoney(order.getGrandTotal(), order.getCurrency());
        return "Hi " + name + ", thanks for your order #" + code + ". Total " + amount
                + ". Please complete payment and we'll get it ready. Reply here if you need help.";
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        String cur = currency == null || currency.isBlank() ? "KES" : currency.trim();
        return cur + " " + nf.format(scaled);
    }
}
