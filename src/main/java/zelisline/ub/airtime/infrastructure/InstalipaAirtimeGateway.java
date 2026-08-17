package zelisline.ub.airtime.infrastructure;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

/**
 * Instalipa airtime API client.
 *
 * <p>Auth is HTTP Basic on {@code /api/v1/token}, which returns a bearer token
 * valid for an hour; tokens are cached per consumer key just short of their TTL,
 * the same way {@code KopokopoPaymentGateway} handles OAuth.
 *
 * <p>Instalipa answers a recharge with {@code Submitted}/{@code Pending} and
 * confirms the real outcome later on the registered callback URL, so callers must
 * treat a successful POST as "in flight", never as "delivered".
 */
@Component
public class InstalipaAirtimeGateway {

    private static final Logger log = LoggerFactory.getLogger(InstalipaAirtimeGateway.class);

    private static final String TOKEN_PATH = "/api/v1/token";
    private static final String AIRTIME_PATH = "/api/v1/airtime";
    private static final String STATUS_PATH = "/api/v1/status/";

    private static final String USER_AGENT = "Palmart/1.0";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HTTP_SOCKET_TIMEOUT_MS = 20_000;

    /** Instalipa's own vocabulary from the status-code table. */
    private static final String PROVIDER_SUCCESS = "success";
    private static final String PROVIDER_FAILED = "failed";
    private static final String PROVIDER_SUBMITTED = "submitted";
    private static final String PROVIDER_PENDING = "pending";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** key = baseUrl + ":" + consumerKey */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * Outcome of a recharge or status query, normalised away from Instalipa's
     * string statuses so the application layer never string-matches.
     */
    public record AirtimeResult(
            boolean accepted,
            boolean success,
            boolean terminalFailure,
            String transactionId,
            String providerStatus,
            String details,
            BigDecimal discount,
            BigDecimal floatBalance,
            String receipt,
            String reference,
            String message
    ) {
        public boolean pending() {
            return accepted && !success && !terminalFailure;
        }
    }

    /**
     * Send airtime. A non-2xx response or a transport error yields
     * {@code accepted = false} so the caller can release its wallet hold.
     */
    public AirtimeResult sendAirtime(
            Map<String, String> credentials,
            String baseUrl,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    ) {
        String api = normalizeBase(baseUrl);
        String token;
        try {
            token = obtainAccessToken(credentials, api);
        } catch (Exception e) {
            return rejected("Instalipa authentication failed: " + e.getMessage());
        }

        // Instalipa takes whole numbers only, as a string.
        String amountStr = amount.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        Map<String, String> body = Map.of(
                "phone_number", phoneNumber,
                "amount", amountStr,
                "reference", reference);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = Unirest.post(api + AIRTIME_PATH)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .header("User-Agent", USER_AGENT)
                    .body(json)
                    .asString();

            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                String detail = extractProviderMessage(response.getBody());
                return rejected("Instalipa rejected the recharge (HTTP "
                        + response.getStatus() + ")" + (detail != null ? ": " + detail : ""));
            }
            return parse(response.getBody(), true);
        } catch (Exception e) {
            log.warn("Instalipa airtime send failed ref={}: {}", reference, e.getMessage());
            return rejected("Could not reach Instalipa: " + e.getMessage());
        }
    }

    /** Poll a transaction when the callback never arrived. */
    public AirtimeResult queryStatus(
            Map<String, String> credentials,
            String baseUrl,
            String transactionId
    ) {
        String api = normalizeBase(baseUrl);
        String token;
        try {
            token = obtainAccessToken(credentials, api);
        } catch (Exception e) {
            return rejected("Instalipa authentication failed: " + e.getMessage());
        }

        try {
            HttpResponse<String> response = Unirest.get(api + STATUS_PATH + transactionId.trim())
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();

            if (response.getStatus() == 404) {
                return new AirtimeResult(true, false, true, transactionId, PROVIDER_FAILED,
                        "Not found at provider", null, null, null, null,
                        "Instalipa does not recognise this transaction");
            }
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                return rejected("Instalipa status query failed (HTTP " + response.getStatus() + ")");
            }
            return parse(response.getBody(), true);
        } catch (Exception e) {
            log.warn("Instalipa status query failed txn={}: {}", transactionId, e.getMessage());
            return rejected("Could not reach Instalipa: " + e.getMessage());
        }
    }

    /** Parse a callback body posted to our webhook endpoint. */
    public AirtimeResult parseCallback(String rawBody) {
        return parse(rawBody, true);
    }

    /**
     * Verify credentials by asking for a token. Returns null when they work,
     * otherwise a message suitable for showing a super-admin.
     */
    public String validateCredentials(Map<String, String> credentials, String baseUrl) {
        try {
            obtainAccessToken(credentials, normalizeBase(baseUrl));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private AirtimeResult parse(String rawBody, boolean accepted) {
        if (rawBody == null || rawBody.isBlank()) {
            return rejected("Empty response from Instalipa");
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            String status = text(node, "status");
            String details = text(node, "details");
            String normalized = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);

            boolean success = PROVIDER_SUCCESS.equals(normalized);
            boolean inFlight = PROVIDER_SUBMITTED.equals(normalized) || PROVIDER_PENDING.equals(normalized);
            // Anything that is neither success nor an explicit in-flight state is
            // terminal — including "Failed" and the duplicate-request rejection.
            boolean terminalFailure = !success && !inFlight;

            return new AirtimeResult(
                    accepted,
                    success,
                    terminalFailure,
                    text(node, "transaction_id"),
                    status,
                    details,
                    decimal(node, "discount"),
                    decimal(node, "balance"),
                    text(node, "receipt"),
                    text(node, "reference"),
                    details != null ? details : status);
        } catch (Exception e) {
            return rejected("Unreadable response from Instalipa: " + e.getMessage());
        }
    }

    private static AirtimeResult rejected(String message) {
        return new AirtimeResult(false, false, false, null, null, null, null, null, null, null, message);
    }

    private String obtainAccessToken(Map<String, String> credentials, String baseUrl) {
        String consumerKey = value(credentials, "consumerKey");
        String consumerSecret = value(credentials, "consumerSecret");
        if (consumerKey == null || consumerSecret == null) {
            throw new IllegalArgumentException("consumerKey and consumerSecret are required");
        }

        String cacheKey = baseUrl + ":" + consumerKey;
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.token;
        }

        String basic = Base64.getEncoder().encodeToString(
                (consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = Unirest.post(baseUrl + TOKEN_PATH)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT_MS)
                    .socketTimeout(HTTP_SOCKET_TIMEOUT_MS)
                    .header("Authorization", "Basic " + basic)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();

            if (response.getStatus() == 401 || response.getStatus() == 403) {
                throw new IllegalStateException("Instalipa rejected the consumer key / secret");
            }
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException("Token request failed (HTTP " + response.getStatus() + ")");
            }

            JsonNode node = objectMapper.readTree(response.getBody());
            JsonNode tokenNode = node.get("access_token");
            if (tokenNode == null || tokenNode.asText().isBlank()) {
                throw new IllegalStateException("Instalipa returned no access_token");
            }
            String token = tokenNode.asText();
            long expiresIn = node.has("expires_in") ? (long) node.get("expires_in").asDouble() : 3600L;
            // Refresh early so an in-flight request never races the expiry.
            Instant expiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 300, 60));
            tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Instalipa token request failed: " + e.getMessage(), e);
        }
    }

    private static String normalizeBase(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://business.instalipa.co.ke"
                : baseUrl.trim();
        return base.replaceAll("/+$", "");
    }

    private static String value(Map<String, String> creds, String key) {
        if (creds == null) {
            return null;
        }
        String v = creds.get(key);
        return v != null && !v.isBlank() ? v.trim() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractProviderMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String details = text(node, "details");
            if (details != null) {
                return details;
            }
            String message = text(node, "message");
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to the raw snippet below.
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
