package zelisline.ub.messaging.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.messaging.application.TenantMessagingConfig;

/**
 * SMS fallback for credit tab reminders (Africa's Talking or Sozuri when configured,
 * otherwise log-only).
 */
@Component
public class SmsMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(SmsMessagingClient.class);
    private static final String DEFAULT_SOZURI_URL = "https://sozuri.net/api/v1/messaging";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param toE164 recipient with leading + (e.g. +254712345678)
     */
    public SendResult sendText(TenantMessagingConfig cfg, String toE164, String body) {
        if (!cfg.smsConfigured()) {
            log.info("SMS stub (provider={}): to={} message={}",
                    cfg.smsProvider(),
                    mask(toE164),
                    truncate(body));
            return SendResult.stubLogged();
        }
        if ("sozuri".equalsIgnoreCase(cfg.smsProvider())) {
            return sendSozuri(toE164, body, cfg);
        }
        return sendAfricasTalking(toE164, body, cfg);
    }

    private SendResult sendAfricasTalking(String toE164, String body, TenantMessagingConfig cfg) {
        try {
            HttpResponse<String> response = Unirest.post("https://api.africastalking.com/version1/messaging")
                    .header("apiKey", cfg.smsApiKey())
                    .header("Accept", "application/json")
                    .field("username", cfg.smsUsername())
                    .field("to", toE164)
                    .field("message", body)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("africas_talking");
            }
            log.warn("Africa's Talking SMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed("http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("Africa's Talking SMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    private SendResult sendSozuri(String toE164, String body, TenantMessagingConfig cfg) {
        String url = blankToDefault(cfg.smsSozuriApiUrl(), DEFAULT_SOZURI_URL);
        String from = blankToDefault(cfg.smsSozuriFrom(), "Sozuri");
        String type = blankToDefault(cfg.smsSozuriType(), "transactional");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("project", cfg.smsSozuriProject());
            payload.put("from", from);
            payload.put("to", toE164);
            payload.put("message", body);
            payload.put("channel", "sms");
            payload.put("type", type);
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + cfg.smsSozuriApiKey().strip())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("sozuri");
            }
            log.warn("Sozuri SMS HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed("http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("Sozuri SMS failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    public record SendResult(boolean sent, boolean stub, String channel, String detail) {
        public static SendResult sent(String channel) {
            return new SendResult(true, false, channel, "sent");
        }

        public static SendResult stubLogged() {
            return new SendResult(false, true, "sms_stub", "logged");
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, "sms", detail);
        }
    }
}
