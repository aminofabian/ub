package zelisline.ub.messaging.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.messaging.application.TenantMessagingConfig;

/**
 * Sends WhatsApp template-free text via Meta Graph API ({@code /messages}).
 */
@Component
public class MetaWhatsAppMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppMessagingClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param toDigits MSISDN without + (e.g. 254712345678)
     */
    public SendResult sendText(TenantMessagingConfig cfg, String toDigits, String body) {
        if (!cfg.metaWhatsAppConfigured()) {
            return SendResult.skipped("Meta WhatsApp not configured");
        }
        if (toDigits == null || toDigits.isBlank() || body == null || body.isBlank()) {
            return SendResult.failed("missing_to_or_body");
        }
        String version = cfg.metaGraphVersion() == null || cfg.metaGraphVersion().isBlank()
                ? "v25.0"
                : cfg.metaGraphVersion();
        String url = "https://graph.facebook.com/" + version + "/" + cfg.metaPhoneNumberId() + "/messages";
        try {
            var payload = java.util.Map.of(
                    "messaging_product", "whatsapp",
                    "to", toDigits,
                    "type", "text",
                    "text", java.util.Map.of("body", body));
            String json = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + cfg.metaAccessToken())
                    .header("Content-Type", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("meta_whatsapp");
            }
            log.warn("Meta WhatsApp send HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed("http_" + response.getStatus());
        } catch (Exception ex) {
            log.warn("Meta WhatsApp send failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public record SendResult(boolean sent, boolean skipped, String channel, String detail) {
        public static SendResult sent(String channel) {
            return new SendResult(true, false, channel, "sent");
        }

        public static SendResult skipped(String detail) {
            return new SendResult(false, true, "whatsapp", detail);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, "whatsapp", detail);
        }
    }
}
