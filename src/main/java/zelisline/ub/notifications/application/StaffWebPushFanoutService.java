package zelisline.ub.notifications.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.domain.DeviceToken;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.infrastructure.FcmSender;
import zelisline.ub.notifications.infrastructure.WebPushSender;
import zelisline.ub.notifications.repository.DeviceTokenRepository;

/**
 * Staff alerts are stored as business-wide inbox rows ({@code user_id} null).
 * Web Push targets each staff user with {@code storefront.orders.read}.
 */
@Service
@RequiredArgsConstructor
public class StaffWebPushFanoutService {

    private static final Logger log = LoggerFactory.getLogger(StaffWebPushFanoutService.class);
    private static final String PERMISSION_STOREFRONT_ORDERS = "storefront.orders.read";

    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final WebPushSender webPushSender;
    private final FcmSender fcmSender;
    private final ObjectMapper objectMapper;

    public void fanoutForStaffAlert(Notification notification) {
        List<String> userIds = userRepository.findIdsWithPermission(
                notification.getBusinessId(),
                PERMISSION_STOREFRONT_ORDERS);
        if (userIds.isEmpty()) {
            return;
        }
        List<DeviceToken> tokens = deviceTokenRepository.findByBusinessIdAndUserIdInAndRevokedAtIsNull(
                notification.getBusinessId(),
                userIds);
        if (tokens.isEmpty()) {
            return;
        }
        ParsedPayload payload = parsePayload(notification.getPayloadJson());
        int webSent = webPushSender.sendToTokens(
                tokens,
                payload.title(),
                payload.body(),
                payload.actionUrl());
        int fcmSent = fcmSender.sendToTokens(tokens, payload.title(), payload.body());
        log.debug(
                "Staff push fan-out: type={} business={} users={} tokens={} webSent={} fcmSent={}",
                notification.getType(),
                notification.getBusinessId(),
                userIds.size(),
                tokens.size(),
                webSent,
                fcmSent);
    }

    private ParsedPayload parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedPayload("Palmart", "", "/storefront/web-orders");
        }
        try {
            var map = objectMapper.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
            return new ParsedPayload(
                    stringVal(map.get("title")),
                    stringVal(map.get("body")),
                    firstNonBlank(stringVal(map.get("actionUrl")), "/storefront/web-orders"));
        } catch (Exception e) {
            return new ParsedPayload("Palmart", "", "/storefront/web-orders");
        }
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
