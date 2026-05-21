package zelisline.ub.notifications.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.Notification;

@Service
@RequiredArgsConstructor
public class StaffEmailFanoutService {

    private static final Logger log = LoggerFactory.getLogger(StaffEmailFanoutService.class);
    private static final String PERMISSION_STOREFRONT_ORDERS = "storefront.orders.read";

    private final UserRepository userRepository;
    private final NotificationService outboundMailService;
    private final ObjectMapper objectMapper;

    public void fanoutForStaffDigest(Notification notification) {
        List<String> userIds = userRepository.findIdsWithPermission(
                notification.getBusinessId(),
                PERMISSION_STOREFRONT_ORDERS);
        if (userIds.isEmpty()) {
            return;
        }
        ParsedPayload payload = parsePayload(notification.getPayloadJson());
        String subject = payload.title() != null && !payload.title().isBlank() ? payload.title() : "Palmart";
        String text = payload.body() != null && !payload.body().isBlank()
                ? payload.title() + ": " + payload.body()
                : payload.title();
        String html = buildHtml(payload);
        int sent = 0;
        for (String userId : userIds) {
            User user = userRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(userId, notification.getBusinessId())
                    .orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            try {
                outboundMailService.sendNotificationEmail(user.getEmail().trim(), subject, text, html);
                sent++;
            } catch (RuntimeException ex) {
                log.warn("Staff digest email failed userId={}: {}", userId, ex.getMessage());
            }
        }
        log.debug("Staff email fan-out: type={} business={} recipients={} sent={}",
                notification.getType(),
                notification.getBusinessId(),
                userIds.size(),
                sent);
    }

    public static boolean isStaffDigestType(String type) {
        return NotificationTypes.ABANDONED_CART.equals(type)
                || NotificationTypes.PEAK_HOURS.equals(type)
                || NotificationTypes.TOP_PRODUCTS.equals(type)
                || "sales.daily_digest".equals(type);
    }

    private ParsedPayload parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedPayload("Palmart", "", "/business/reports");
        }
        try {
            var map = objectMapper.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
            return new ParsedPayload(
                    stringVal(map.get("title")),
                    stringVal(map.get("body")),
                    firstNonBlank(stringVal(map.get("actionUrl")), "/business/reports"));
        } catch (Exception e) {
            return new ParsedPayload("Palmart", "", "/business/reports");
        }
    }

    private static String buildHtml(ParsedPayload payload) {
        String title = payload.title() != null && !payload.title().isBlank() ? payload.title() : "Palmart";
        String body = payload.body() != null ? payload.body() : "";
        String link = payload.actionUrl() != null && !payload.actionUrl().isBlank() ? payload.actionUrl() : "/";
        return """
                <!DOCTYPE html><html><body style="font-family:sans-serif;line-height:1.5">
                <h2>%s</h2><p>%s</p><p><a href="%s">Open in Palmart</a></p>
                </body></html>
                """.formatted(escapeHtml(title), escapeHtml(body), escapeHtml(link));
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stringVal(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static String firstNonBlank(String a, String fallback) {
        return a != null && !a.isBlank() ? a : fallback;
    }

    private record ParsedPayload(String title, String body, String actionUrl) {
    }
}
