package zelisline.ub.notifications.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class StaffEmailFanoutService {

    private static final Logger log = LoggerFactory.getLogger(StaffEmailFanoutService.class);
    private static final String PERMISSION_STOREFRONT_ORDERS = "storefront.orders.read";

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final AbandonedCartDigestEmailRenderer abandonedCartDigestEmailRenderer;
    private final NotificationService outboundMailService;
    private final ObjectMapper objectMapper;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public void fanoutForStaffDigest(Notification notification) {
        List<String> userIds = userRepository.findIdsWithPermission(
                notification.getBusinessId(),
                PERMISSION_STOREFRONT_ORDERS);
        if (userIds.isEmpty()) {
            return;
        }
        ParsedPayload payload = parsePayload(notification.getPayloadJson());
        DigestEmail email = buildDigestEmail(notification, payload);
        int sent = 0;
        for (String userId : userIds) {
            User user = userRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(userId, notification.getBusinessId())
                    .orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                continue;
            }
            try {
                outboundMailService.sendNotificationEmail(
                        user.getEmail().trim(), email.subject(), email.text(), email.html());
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

    private DigestEmail buildDigestEmail(Notification notification, ParsedPayload payload) {
        if (NotificationTypes.ABANDONED_CART.equals(notification.getType())) {
            return buildAbandonedCartEmail(notification.getBusinessId(), payload);
        }
        String subject = payload.title() != null && !payload.title().isBlank() ? payload.title() : "Palmart";
        String text = payload.body() != null && !payload.body().isBlank()
                ? payload.title() + ": " + payload.body()
                : payload.title();
        return new DigestEmail(subject, text, buildGenericHtml(payload));
    }

    private DigestEmail buildAbandonedCartEmail(String businessId, ParsedPayload payload) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        TenantBrandingDto branding = TenantBrandingDto.defaults(
                business != null && business.getName() != null ? business.getName() : "Your store");
        String fallbackName = branding.displayName();
        String slug = null;
        if (business != null) {
            branding = storefrontSettingsService
                    .readTenantConfig(business.getSettings(), business.getName())
                    .branding();
            fallbackName = business.getName();
            slug = business.getSlug();
        }

        long cartCount = parseLong(payload.cartCount(), 0L);
        List<AbandonedCartDigestEmailRenderer.ItemPreview> items = parseItemPreviews(payload.itemsJson());
        String actionUrl = absoluteActionUrl(payload.actionUrl());

        String subject = abandonedCartDigestEmailRenderer.renderSubject(branding, fallbackName, slug);
        String text = abandonedCartDigestEmailRenderer.renderPlainText(
                branding, fallbackName, slug, cartCount, items, actionUrl);
        String html = abandonedCartDigestEmailRenderer.renderHtml(
                branding, fallbackName, slug, cartCount, items, actionUrl);
        return new DigestEmail(subject, text, html);
    }

    private ParsedPayload parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedPayload("Palmart", "", "/business/reports", null, null);
        }
        try {
            var map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return new ParsedPayload(
                    stringVal(map.get("title")),
                    stringVal(map.get("body")),
                    firstNonBlank(stringVal(map.get("actionUrl")), "/business/reports"),
                    stringVal(map.get("cartCount")),
                    stringVal(map.get("itemsJson")));
        } catch (Exception e) {
            return new ParsedPayload("Palmart", "", "/business/reports", null, null);
        }
    }

    private List<AbandonedCartDigestEmailRenderer.ItemPreview> parseItemPreviews(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(itemsJson, new TypeReference<>() {});
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<AbandonedCartDigestEmailRenderer.ItemPreview> out = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                String name = stringVal(row.get("name"));
                if (name.isBlank()) {
                    continue;
                }
                out.add(new AbandonedCartDigestEmailRenderer.ItemPreview(
                        stringVal(row.get("itemId")),
                        name,
                        blankToNull(stringVal(row.get("variantName"))),
                        blankToNull(stringVal(row.get("imageUrl"))),
                        parseQuantity(row.get("quantity")),
                        parseLong(row.get("cartCount"), 0L)));
            }
            return out;
        } catch (Exception e) {
            log.debug("Could not parse abandoned cart itemsJson: {}", e.getMessage());
            return List.of();
        }
    }

    private String absoluteActionUrl(String actionUrl) {
        String path = actionUrl != null && !actionUrl.isBlank() ? actionUrl.trim() : "/storefront/web-orders";
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String base = frontendBaseUrl != null ? frontendBaseUrl.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base.isBlank() ? path : base + path;
    }

    private static String buildGenericHtml(ParsedPayload payload) {
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String a, String fallback) {
        return a != null && !a.isBlank() ? a : fallback;
    }

    private static long parseLong(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            String s = String.valueOf(raw).trim();
            if (s.isBlank()) {
                return fallback;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BigDecimal parseQuantity(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            String s = String.valueOf(raw).trim();
            if (s.isBlank()) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private record ParsedPayload(
            String title,
            String body,
            String actionUrl,
            String cartCount,
            String itemsJson
    ) {
    }

    private record DigestEmail(String subject, String text, String html) {
    }
}
