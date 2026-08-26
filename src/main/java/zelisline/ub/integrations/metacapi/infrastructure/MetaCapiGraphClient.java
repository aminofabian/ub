package zelisline.ub.integrations.metacapi.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.integrations.metacapi.config.MetaCapiProperties;

/**
 * Sends events to the Meta Conversions API ({@code /{version}/{pixelId}/events}).
 *
 * <p>Same conventions as {@code MetaWhatsAppMessagingClient}: Unirest, Bearer
 * token, explicit connect/socket timeouts, Meta {@code error{message,type,code,
 * error_subcode}} envelope parsing, and truncated body logging so PII (IP, UA,
 * fbp/fbc) never lands in application logs. The full response body is returned
 * in the result for the restricted super-admin delivery log.
 */
@Component
public class MetaCapiGraphClient {

    private static final Logger log = LoggerFactory.getLogger(MetaCapiGraphClient.class);

    private static final String API_BASE = "https://graph.facebook.com";

    private final MetaCapiProperties properties;

    public MetaCapiGraphClient(MetaCapiProperties properties) {
        this.properties = properties;
    }

    public SendResult send(String pixelId, String accessToken, String requestJson) {
        String url = API_BASE + "/" + properties.graphVersion() + "/" + pixelId + "/events";
        try {
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .connectTimeout(properties.connectTimeoutMs())
                    .socketTimeout(properties.socketTimeoutMs())
                    .body(requestJson)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent(response.getBody());
            }
            String detail = formatHttpFailure(response.getStatus(), response.getBody());
            log.warn("Meta CAPI send HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed(detail, response.getStatus(), response.getBody());
        } catch (Exception ex) {
            log.warn("Meta CAPI send failed: {}", ex.getMessage());
            return SendResult.failed("network_error: " + ex.getMessage(), null, null);
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
            String code = error.has("code") && !error.get("code").isNull()
                    ? error.get("code").asText()
                    : null;
            String subcode = error.has("error_subcode") && !error.get("error_subcode").isNull()
                    ? error.get("error_subcode").asText()
                    : null;
            StringBuilder sb = new StringBuilder();
            if (type != null && !type.isBlank()) {
                sb.append(type).append(" — ");
            }
            if (message != null) {
                sb.append(message);
            }
            if (code != null || subcode != null) {
                sb.append(" [code=").append(code != null ? code : "?");
                if (subcode != null) {
                    sb.append(" subcode=").append(subcode);
                }
                sb.append(']');
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
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

    static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /**
     * {@code retryable} is true for network errors and transient HTTP statuses
     * (429, 5xx). Auth failures (401/403) and other 4xx are terminal — the
     * payload or credentials won't improve by retrying.
     */
    public record SendResult(
            boolean sent,
            String detail,
            Integer httpStatus,
            String responseBody,
            boolean retryable
    ) {

        public static SendResult sent(String responseBody) {
            return new SendResult(true, "sent", 200, responseBody, true);
        }

        public static SendResult failed(String detail, Integer httpStatus, String responseBody) {
            boolean retryable = httpStatus == null
                    || httpStatus == 429
                    || httpStatus >= 500;
            return new SendResult(false, detail, httpStatus, responseBody, retryable);
        }

        public boolean authFailure() {
            return httpStatus != null && (httpStatus == 401 || httpStatus == 403);
        }
    }
}
