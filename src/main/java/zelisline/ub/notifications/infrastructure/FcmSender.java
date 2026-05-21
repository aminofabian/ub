package zelisline.ub.notifications.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.config.FcmProperties;
import zelisline.ub.notifications.domain.DeviceToken;

@Component
@RequiredArgsConstructor
public class FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final String FCM_LEGACY_URL = "https://fcm.googleapis.com/fcm/send";

    private final FcmProperties fcmProperties;
    private final ObjectMapper objectMapper;

    public int sendToTokens(List<DeviceToken> tokens, String title, String body) {
        if (!fcmProperties.configured()) {
            return 0;
        }
        int sent = 0;
        for (DeviceToken token : tokens) {
            if (!isMobilePlatform(token.getPlatform())) {
                continue;
            }
            if (send(token.getToken(), title, body).sent()) {
                sent++;
            }
        }
        return sent;
    }

    public SendResult send(String fcmToken, String title, String body) {
        if (!fcmProperties.configured()) {
            return SendResult.skipped("fcm_not_configured");
        }
        if (fcmToken == null || fcmToken.isBlank()) {
            return SendResult.failed("missing_token");
        }
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("title", title != null && !title.isBlank() ? title : "Palmart");
            notification.put("body", body != null ? body : "");
            Map<String, Object> payload = new HashMap<>();
            payload.put("to", fcmToken.trim());
            payload.put("notification", notification);
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(FCM_LEGACY_URL)
                    .header("Authorization", "key=" + fcmProperties.serverKey().trim())
                    .header("Content-Type", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("fcm_legacy");
            }
            log.warn("FCM HTTP {} body={}", response.getStatus(), response.getBody());
            return SendResult.failed("fcm_http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("FCM send failed: {}", ex.getMessage());
            return SendResult.failed("fcm_error");
        }
    }

    public static boolean isMobilePlatform(String platform) {
        return DeviceToken.PLATFORM_ANDROID.equals(platform) || DeviceToken.PLATFORM_IOS.equals(platform);
    }

    public record SendResult(boolean sent, boolean skipped, String detail) {
        public static SendResult sent(String detail) {
            return new SendResult(true, false, detail);
        }

        public static SendResult skipped(String detail) {
            return new SendResult(false, true, detail);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, detail);
        }
    }
}
