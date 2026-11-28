package zelisline.ub.notifications.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.domain.DeviceToken;
import zelisline.ub.notifications.infrastructure.ExpoPushSender;
import zelisline.ub.notifications.repository.DeviceTokenRepository;

/**
 * One notice on the owner and admin phones. The inbox row is the dedupe: a second
 * attempt with the same key does not buzz the phone again. A failure to send must
 * not undo the sale, the shift, or the bill that caused it.
 */
@Service
@RequiredArgsConstructor
public class OwnerDevicePushService {

    private static final Logger log = LoggerFactory.getLogger(OwnerDevicePushService.class);

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ExpoPushSender expoPushSender;
    private final ObjectMapper objectMapper;

    public void pushOnce(String businessId, String type, String dedupeKey, String title, String body) {
        if (businessId == null || businessId.isBlank() || type == null || dedupeKey == null) {
            return;
        }
        try {
            String payload = payload(type, title, body);
            if (notificationService.tryInsertDedupe(businessId, type, dedupeKey, "operational", "HIGH", payload).isEmpty()) {
                return;
            }
            LinkedHashSet<String> userIds = new LinkedHashSet<>();
            for (String role : List.of(IdentityService.OWNER_ROLE_KEY, IdentityService.ADMIN_ROLE_KEY)) {
                for (User user : userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(businessId, role)) {
                    if (user.getId() != null) {
                        userIds.add(user.getId());
                    }
                }
            }
            if (userIds.isEmpty()) {
                return;
            }
            List<DeviceToken> tokens = deviceTokenRepository.findByBusinessIdAndUserIdInAndRevokedAtIsNull(
                    businessId, List.copyOf(userIds));
            int sent = expoPushSender.sendToTokens(tokens, title, body, type);
            log.debug("Owner push type={} business={} tokens={} sent={}", type, businessId, tokens.size(), sent);
        } catch (RuntimeException ex) {
            log.warn("Owner push failed type={} business={}", type, businessId, ex);
        }
    }

    private String payload(String type, String title, String body) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("title", title == null ? "" : title);
        row.put("body", body == null ? "" : body);
        row.put("kind", type);
        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception ex) {
            return "{\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body) + "\"}";
        }
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
