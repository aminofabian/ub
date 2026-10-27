package zelisline.ub.notifications.application;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How one notification row reads in the bell: title, body, destination.
 *
 * <p>The WebSocket frame and the REST bell list must describe the same row the
 * same way, so both call this. Before, the bridge carried a private copy of the
 * title map and the REST list returned raw {@code payloadJson} — the client
 * then downloaded every historical payload and re-derived the copy itself.
 *
 * <p>This is a resolver, not a formatter: it reads the presentation strings the
 * producers already put in the payload ({@code title}, {@code body},
 * {@code actionUrl}/{@code paymentUrl}) and falls back to the type default. The
 * client keeps its richer per-type formatting for structured rows, which needs
 * the full payload and therefore only runs where the payload is present.
 */
@Component
public class NotificationPresenter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Presentation present(String type, String payloadJson) {
        String title = defaultTitleForType(type);
        String body = "";
        String actionUrl = "";

        if (usesPayloadPresentation(type) && payloadJson != null && !payloadJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(payloadJson, Map.class);
                title = stringOr(parsed.get("title"), title);
                body = stringOr(parsed.get("body"), "");
                actionUrl = stringOr(parsed.get("actionUrl"),
                        stringOr(parsed.get("paymentUrl"), ""));
            } catch (Exception ignored) {
                // A malformed payload must never blank the row: keep the type default.
            }
        }

        return new Presentation(title, body, actionUrl);
    }

    public record Presentation(String title, String body, String actionUrl) {}

    /**
     * Types whose copy is authored in the payload by
     * {@link NotificationTemplateRenderer}, rather than a static label.
     */
    private static boolean usesPayloadPresentation(String type) {
        return switch (type) {
            case "credit_sale.reminder", "order.received", "order.payment_received",
                 "order.confirmed", "order.dispatched", "order.delivered",
                 "storefront.order.placed", "storefront.order.paid", "stock.low",
                 "sales.daily_digest", "inventory.restock_digest", "account.welcome",
                 "onboarding.fill_shelf", "onboarding.sizes_right", "onboarding.money_loop",
                 "onboarding.first_sale", "onboarding.go_live", "onboarding.team_rhythm",
                 "onboarding.week_checkin", "onboarding.reengage", "onboarding.lookalike",
                 "onboarding.close_shift", "onboarding.web_order", "till.slip" -> true;
            default -> false;
        };
    }

    private static String defaultTitleForType(String type) {
        return switch (type) {
            case "payable.overdue" -> "Overdue supplier payments";
            case "receivable.overdue" -> "Overdue customer payments";
            case "shift.variance_detected" -> "Shift cash variance detected";
            case "stock.low" -> "Low stock alert";
            case "batch.expiring" -> "Expiring stock alert";
            case "storefront.order.placed" -> "New web order";
            case "storefront.order.paid" -> "Web order paid";
            case "order.received" -> "Order received";
            case "order.payment_received" -> "Payment received";
            case "order.confirmed" -> "Order confirmed";
            case "order.dispatched" -> "Ready for pickup";
            case "order.delivered" -> "Order complete";
            case "approval.requested" -> "Approval requested";
            case "approval.resolved" -> "Approval resolved";
            case "export.completed" -> "Export ready";
            case "credit_sale.reminder" -> "Credit purchase";
            case "sales.daily_digest" -> "Daily sales summary";
            case "inventory.restock_digest" -> "Tonight's list";
            case "account.welcome" -> "Welcome to Kiosk!";
            case "till.slip" -> "Print on this till";
            default -> type;
        };
    }

    private static String stringOr(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? fallback : s;
    }
}
