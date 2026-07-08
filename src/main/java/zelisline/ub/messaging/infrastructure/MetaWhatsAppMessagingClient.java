package zelisline.ub.messaging.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
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
        if (body == null || body.isBlank()) {
            return SendResult.failed("missing_body");
        }
        return send(cfg, toDigits, java.util.Map.of(
                "type", "text",
                "text", java.util.Map.of("body", body)));
    }

    /**
     * Sends a pre-approved template message (required for cold outreach outside the
     * 24-hour customer service window).
     */
    public SendResult sendTemplate(TenantMessagingConfig cfg, String toDigits, String templateName, String languageCode) {
        if (templateName == null || templateName.isBlank()) {
            return SendResult.failed("missing_template_name");
        }
        String lang = languageCode == null || languageCode.isBlank() ? "en" : languageCode.trim();
        return send(cfg, toDigits, java.util.Map.of(
                "type", "template",
                "template", java.util.Map.of(
                        "name", templateName.trim(),
                        "language", java.util.Map.of("code", lang))));
    }

    private SendResult send(TenantMessagingConfig cfg, String toDigits, Object payload) {
        if (!cfg.metaWhatsAppConfigured()) {
            return SendResult.skipped("Meta WhatsApp not configured");
        }
        if (toDigits == null || toDigits.isBlank()) {
            return SendResult.failed("missing_to");
        }
        String token = cfg.metaAccessToken() == null ? "" : cfg.metaAccessToken().trim();
        if (token.isBlank()) {
            return SendResult.failed("missing_access_token");
        }
        String version = cfg.metaGraphVersion() == null || cfg.metaGraphVersion().isBlank()
                ? "v25.0"
                : cfg.metaGraphVersion().trim();
        String phoneNumberId = cfg.metaPhoneNumberId() == null ? "" : cfg.metaPhoneNumberId().trim();
        String url = "https://graph.facebook.com/" + version + "/" + phoneNumberId + "/messages";
        try {
            var requestBody = new java.util.LinkedHashMap<String, Object>();
            requestBody.put("messaging_product", "whatsapp");
            requestBody.put("to", toDigits.trim());
            requestBody.putAll((java.util.Map<String, Object>) payload);
            String json = objectMapper.writeValueAsString(requestBody);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("meta_whatsapp");
            }
            String detail = formatHttpFailure(response.getStatus(), response.getBody());
            log.warn("Meta WhatsApp send HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed(detail, response.getStatus());
        } catch (Exception ex) {
            log.warn("Meta WhatsApp send failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    static String formatHttpFailure(int status, String rawBody) {
        String metaMessage = parseMetaErrorMessage(rawBody);
        if (metaMessage == null || metaMessage.isBlank()) {
            return "http_" + status;
        }
        return "http_" + status + ": " + metaMessage;
    }

    static String parseMetaErrorMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(rawBody);
            JsonNode error = root.get("error");
            if (error == null || error.isNull()) {
                return null;
            }
            String message = textField(error, "message");
            String type = textField(error, "type");
            if (message == null) {
                return null;
            }
            if (type == null || type.isBlank()) {
                return message;
            }
            return type + " — " + message;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String textField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public record SendResult(boolean sent, boolean skipped, String channel, String detail, Integer httpStatus) {
        public static SendResult sent(String channel) {
            return new SendResult(true, false, channel, "sent", null);
        }

        public static SendResult skipped(String detail) {
            return new SendResult(false, true, "whatsapp", detail, null);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, "whatsapp", detail, null);
        }

        public static SendResult failed(String detail, int httpStatus) {
            return new SendResult(false, false, "whatsapp", detail, httpStatus);
        }

        public boolean authFailure() {
            return httpStatus != null && (httpStatus == 401 || httpStatus == 403);
        }
    }
}
