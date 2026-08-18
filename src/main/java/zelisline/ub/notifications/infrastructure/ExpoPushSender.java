package zelisline.ub.notifications.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.domain.DeviceToken;

@Component
@RequiredArgsConstructor
public class ExpoPushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);
    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    private final ObjectMapper objectMapper;

    public static boolean isExpoPushToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        return trimmed.startsWith("ExponentPushToken[") || trimmed.startsWith("ExpoPushToken[");
    }

    public int sendToTokens(List<DeviceToken> tokens, String title, String body) {
        int sent = 0;
        for (DeviceToken token : tokens) {
            if (!isExpoPushToken(token.getToken())) {
                continue;
            }
            if (!FcmSender.isMobilePlatform(token.getPlatform())) {
                continue;
            }
            if (send(token.getToken(), title, body).sent()) {
                sent++;
            }
        }
        return sent;
    }

    public FcmSender.SendResult send(String expoToken, String title, String body) {
        if (!isExpoPushToken(expoToken)) {
            return FcmSender.SendResult.skipped("not_expo_token");
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("to", expoToken.trim());
            payload.put("title", title != null && !title.isBlank() ? title : "Palmart");
            payload.put("body", body != null ? body : "");
            payload.put("sound", "default");
            payload.put("channelId", "default");
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(EXPO_PUSH_URL)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip, deflate")
                    .body(json)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("Expo push HTTP {} body={}", response.getStatus(), response.getBody());
                return FcmSender.SendResult.failed("expo_http_" + response.getStatus());
            }
            if (expoTicketFailed(response.getBody())) {
                log.warn("Expo push ticket error body={}", response.getBody());
                return FcmSender.SendResult.failed("expo_ticket_error");
            }
            return FcmSender.SendResult.sent("expo");
        } catch (Exception ex) {
            log.warn("Expo push send failed: {}", ex.getMessage());
            return FcmSender.SendResult.failed("expo_error");
        }
    }

    private boolean expoTicketFailed(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.get("data");
            if (data == null) {
                return false;
            }
            if (data.isArray() && data.size() > 0) {
                return "error".equalsIgnoreCase(text(data.get(0), "status"));
            }
            return "error".equalsIgnoreCase(text(data, "status"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }
}
