package zelisline.ub.notifications.application;

import java.math.BigDecimal;

/**
 * Published after a web-order notification is processed (see
 * {@link NotificationOrchestrator}), so the WhatsApp CRM can run {@code SHOP_EVENT}
 * automations in reaction to real shop events — the "order placed → confirm" gap left
 * open by M3 (see {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.6).
 *
 * <p>Consumed by {@code zelisline.ub.crm.application.CrmOrderEventListener}. The event
 * vocabulary is stable and is what a rule stores in its trigger config, e.g.
 * {@code {"event":"ORDER_PLACED"}}.
 */
public record WebOrderLifecycleEvent(
        String businessId,
        String eventName,
        String orderId,
        String orderCode,
        String customerName,
        String customerPhone,
        String currency,
        BigDecimal grandTotal
) {

    public static final String ORDER_PLACED = "ORDER_PLACED";
    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_DISPATCHED = "ORDER_DISPATCHED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
}
