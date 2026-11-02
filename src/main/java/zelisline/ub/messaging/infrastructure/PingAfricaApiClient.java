package zelisline.ub.messaging.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

/**
 * Thin Ping Africa Bulk API helper (credits balance). SMS send stays in
 * {@link SmsMessagingClient}.
 *
 * @see <a href="https://bulk.ping.africa/docs/api">Ping Africa API reference</a>
 */
@Component
public class PingAfricaApiClient {

    private static final Logger log = LoggerFactory.getLogger(PingAfricaApiClient.class);
    private static final String DEFAULT_BASE_URL = "https://bulk.ping.africa/api";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@code GET {base}/credits/balance} → {@code { "balance": 106.6 }}.
     */
    public CreditsResult fetchCredits(String apiKey, String baseUrl) {
        String token = stripBearerPrefix(apiKey);
        if (token.isBlank()) {
            return CreditsResult.failed("Ping Africa API token is not configured");
        }
        String url = stripTrailingSlashes(blankToDefault(baseUrl, DEFAULT_BASE_URL)) + "/credits/balance";
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                String reason = parseError(response.getBody());
                log.warn("Ping Africa credits HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
                return CreditsResult.failed(
                        reason == null
                                ? "http_" + response.getStatus()
                                : "http_" + response.getStatus() + ":" + reason,
                        response.getStatus());
            }
            Double balance = parseBalance(response.getBody());
            if (balance == null) {
                return CreditsResult.failed("Could not parse Ping Africa balance response", response.getStatus());
            }
            return CreditsResult.ok(balance, response.getStatus());
        } catch (Exception ex) {
            log.warn("Ping Africa credits failed: {}", ex.getMessage());
            return CreditsResult.failed(ex.getMessage() == null ? "error" : ex.getMessage());
        }
    }

    private Double parseBalance(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode balance = root.get("balance");
            if (balance == null || balance.isNull()) {
                return null;
            }
            if (balance.isNumber()) {
                return balance.asDouble();
            }
            if (balance.isTextual()) {
                String text = balance.asText("").trim();
                if (text.isEmpty()) {
                    return null;
                }
                return Double.parseDouble(text);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String parseError(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode error = root.get("error");
            if (error != null && error.isTextual() && !error.asText("").isBlank()) {
                return truncate(error.asText());
            }
            JsonNode message = root.get("message");
            if (message != null && message.isTextual() && !message.asText("").isBlank()) {
                return truncate(message.asText());
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stripBearerPrefix(String token) {
        if (token == null) {
            return "";
        }
        return token.trim().replaceFirst("(?i)^Bearer\\s+", "").trim();
    }

    private static String stripTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    public record CreditsResult(boolean ok, Double balance, String detail, int httpStatus) {
        public static CreditsResult ok(double balance, int httpStatus) {
            return new CreditsResult(true, balance, null, httpStatus);
        }

        public static CreditsResult failed(String detail) {
            return new CreditsResult(false, null, detail, 0);
        }

        public static CreditsResult failed(String detail, int httpStatus) {
            return new CreditsResult(false, null, detail, httpStatus);
        }
    }
}
