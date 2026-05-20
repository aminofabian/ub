package zelisline.ub.payments.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.domain.spi.StkStatusResponse;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;

/**
 * KopoKopo payment gateway implementation.
 *
 * <p>Handles:
 * <ul>
 *   <li>OAuth client-credentials flow (token cached for 55 min)</li>
 *   <li>Incoming payment (STK Push) via {@code POST /api/v2/incoming_payments}</li>
 *   <li>Payment status query via {@code GET /api/v2/incoming_payments/{id}}</li>
 *   <li>Webhook verification via HMAC-SHA256 of body with API key</li>
 *   <li>Connection test via OAuth token endpoint ping</li>
 * </ul>
 *
 * <p>Environment URLs (production uses two hosts per KopoKopo docs):
 * <ul>
 *   <li>Sandbox: {@code https://sandbox.kopokopo.com} for OAuth and API</li>
 *   <li>Production OAuth: {@code https://app.kopokopo.com/oauth/token}</li>
 *   <li>Production API: {@code https://api.kopokopo.com/api/v2/...}</li>
 * </ul>
 */
@Component
public class KopokopoPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(KopokopoPaymentGateway.class);

    private static final String SANDBOX_BASE = "https://sandbox.kopokopo.com";
    private static final String PRODUCTION_AUTH_BASE = "https://app.kopokopo.com";
    private static final String PRODUCTION_API_BASE = "https://api.kopokopo.com";

    private static final String OAUTH_PATH = "/oauth/token";
    private static final String INCOMING_PAYMENTS_PATH = "/api/v2/incoming_payments";
    private static final String USER_AGENT = "PalMart/1.0 KopoKopo";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Simple in-memory token cache: key = clientId, value = cached token with expiry. */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @Override
    public String gatewayType() {
        return GatewayType.KOPOKOPO.name();
    }

    // ── STK Push ─────────────────────────────────────────────────────

    @Override
    public StkPushResponse initiateStkPush(StkPushRequest request) {
        Map<String, String> creds = request.credentials();
        String authBase = resolveAuthBaseUrl(creds);
        String apiBase = resolveApiBaseUrl(creds);
        String accessToken = obtainAccessToken(creds, authBase);
        String tillNumber = creds.getOrDefault("tillNumber", creds.get("shortcode"));

        if (tillNumber == null || tillNumber.isBlank()) {
            return StkPushResponse.rejected("MISSING_TILL", "tillNumber is required in credentials");
        }

        // Normalize phone: strip leading +, ensure E.164-ish
        String phone = request.phoneNumber();
        if (phone == null || phone.isBlank()) {
            return StkPushResponse.rejected("MISSING_PHONE", "phoneNumber is required");
        }
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "254" + phone.substring(1);
        }
        if (!phone.startsWith("254")) {
            phone = "254" + phone;
        }

        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> body = Map.of(
                "payment_channel", "M-PESA STK Push",
                "till_number", tillNumber,
                "subscriber", Map.of(
                        "first_name", "Customer",
                        "last_name", "",
                        "phone_number", "+" + phone,
                        "email", ""
                ),
                "amount", Map.of(
                        "currency", "KES",
                        "value", amount.intValue()
                ),
                "metadata", Map.of(
                        "reference", request.reference() != null ? request.reference() : "",
                        "notes", request.description() != null ? request.description() : "Wallet Top Up"
                ),
                "_links", Map.of(
                        "callback_url", request.callbackBaseUrl() + "/webhooks/kopokopo/payment"
                )
        );

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = Unirest.post(apiBase + INCOMING_PAYMENTS_PATH)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .body(json)
                    .asString();

            if (response.getStatus() == 201) {
                String location = response.getHeaders().getFirst("Location");
                String paymentId = extractIdFromLocation(location);
                log.info("KopoKopo STK Push initiated: paymentId={} ref={}", paymentId, request.reference());
                return StkPushResponse.accepted(paymentId, null, "0", "Accepted");
            }

            // Parse error body
            String errorMsg = parseKopokopoError(response.getBody());
            log.warn("KopoKopo STK Push rejected: status={} body={}", response.getStatus(), response.getBody());
            return StkPushResponse.rejected(String.valueOf(response.getStatus()), errorMsg);
        } catch (Exception e) {
            log.error("KopoKopo STK Push failed", e);
            return StkPushResponse.rejected("NETWORK_ERROR", e.getMessage());
        }
    }

    // ── Status Query ─────────────────────────────────────────────────

    @Override
    public StkStatusResponse queryStkStatus(String gatewayCheckoutRequestId) {
        // KopoKopo uses incoming_payments/{id} for status
        // We don't have access to credentials here, so this is a limitation.
        // For now, return a pending status — the polling scheduler will use
        // the stored config to decrypt credentials and call the API directly.
        return new StkStatusResponse("PENDING", "Status unknown without credentials",
                false, false, null);
    }

    /**
     * Query STK status with credentials (called by the polling scheduler).
     */
    public StkStatusResponse queryStkStatus(String paymentId, Map<String, String> creds) {
        String authBase = resolveAuthBaseUrl(creds);
        String apiBase = resolveApiBaseUrl(creds);
        String accessToken = obtainAccessToken(creds, authBase);
        String url = apiBase + INCOMING_PAYMENTS_PATH + "/" + paymentId;

        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();

            if (response.getStatus() != 200) {
                return new StkStatusResponse("ERROR", "HTTP " + response.getStatus(),
                        false, true, response.getBody());
            }

            var root = objectMapper.readTree(response.getBody());
            var data = root.get("data");
            if (data == null) {
                return new StkStatusResponse("PENDING", "No data", false, false, response.getBody());
            }

            var attrs = data.get("attributes");
            if (attrs == null) {
                return new StkStatusResponse("PENDING", "No attributes", false, false, response.getBody());
            }

            String status = attrs.has("status") ? attrs.get("status").asText() : "Pending";

            boolean completed = "Success".equalsIgnoreCase(status);
            boolean failed = "Failed".equalsIgnoreCase(status);

            return new StkStatusResponse(status, status, completed, failed, response.getBody());
        } catch (Exception e) {
            log.error("KopoKopo status query failed: paymentId={}", paymentId, e);
            return new StkStatusResponse("ERROR", e.getMessage(), false, true, null);
        }
    }

    // ── Webhook ──────────────────────────────────────────────────────

    @Override
    public WebhookResult processWebhook(Map<String, String> headers, String rawBody) {
        try {
            var root = objectMapper.readTree(rawBody);

            // STK callback / status payload: { "data": { "id", "attributes": { status, metadata, event } } }
            if (root.has("data") && root.get("data").isObject()) {
                return parseIncomingPaymentData(root.get("data"), null, rawBody);
            }

            // K2Connect topic webhook: { topic, id, event: { resource } }
            if (root.has("topic") && root.has("event")) {
                String topic = root.get("topic").asText();
                String webhookEventId = root.has("id") ? root.get("id").asText() : null;
                var resource = root.get("event").get("resource");

                if (resource == null || resource.isNull()) {
                    return WebhookResult.empty(rawBody);
                }

                String gatewayTxnId = resource.has("id") ? resource.get("id").asText() : null;
                String phone = normalizeWebhookPhone(textOrNull(resource, "sender_phone_number"));
                BigDecimal amount = parseAmount(textOrNull(resource, "amount"));
                String reference = textOrNull(resource, "reference");
                String status = textOrNull(resource, "status");

                boolean success = "buygoods_transaction_received".equals(topic)
                        && "Received".equalsIgnoreCase(status);
                boolean failed = "Failed".equalsIgnoreCase(status);

                return new WebhookResult(
                        null,
                        gatewayTxnId,
                        phone,
                        amount,
                        reference,
                        success,
                        failed,
                        null,
                        webhookEventId,
                        topic,
                        rawBody);
            }

            return WebhookResult.empty(rawBody);
        } catch (Exception e) {
            log.error("KopoKopo webhook parsing failed", e);
            return WebhookResult.empty(rawBody);
        }
    }

    private WebhookResult parseIncomingPaymentData(
            com.fasterxml.jackson.databind.JsonNode data,
            String webhookEventId,
            String rawBody
    ) {
        String checkoutId = data.has("id") ? data.get("id").asText() : null;
        var attrs = data.get("attributes");
        if (attrs == null || attrs.isNull()) {
            return new WebhookResult(
                    null, null, null, null, null, false, false, checkoutId, webhookEventId, null, rawBody);
        }

        String status = textOrNull(attrs, "status");
        String reference = null;
        if (attrs.has("metadata") && attrs.get("metadata").isObject()) {
            reference = textOrNull(attrs.get("metadata"), "reference");
        }

        String phone = null;
        BigDecimal amount = null;
        String gatewayTxnId = null;
        if (attrs.has("event") && attrs.get("event").has("resource")) {
            var resource = attrs.get("event").get("resource");
            if (resource != null && !resource.isNull()) {
                gatewayTxnId = textOrNull(resource, "id");
                phone = normalizeWebhookPhone(textOrNull(resource, "sender_phone_number"));
                amount = parseAmount(textOrNull(resource, "amount"));
                if (reference == null || reference.isBlank()) {
                    reference = textOrNull(resource, "reference");
                }
            }
        }

        boolean success = "Success".equalsIgnoreCase(status)
                || "Received".equalsIgnoreCase(status);
        boolean failed = "Failed".equalsIgnoreCase(status);

        return new WebhookResult(
                null,
                gatewayTxnId,
                phone,
                amount,
                reference,
                success,
                failed,
                checkoutId,
                webhookEventId,
                "incoming_payment",
                rawBody);
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(amountStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeWebhookPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "254" + phone.substring(1);
        }
        return phone;
    }

    // ── Display Instructions (not applicable) ────────────────────────

    @Override
    public DisplayInstructions getDisplayInstructions(String businessId) {
        return null;
    }

    // ── Validate Configuration (Test Connection) ─────────────────────

    @Override
    public ValidationResult validateConfiguration(PaymentGatewayConfig config) {
        String credsJson = config.getCredentialsJson();
        if (credsJson == null || credsJson.isBlank()) {
            return ValidationResult.failure("NO_CREDENTIALS", "No credentials configured", null);
        }

        Map<String, String> creds;
        try {
            creds = objectMapper.readValue(credsJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (JsonProcessingException e) {
            return ValidationResult.failure("INVALID_JSON", "Credentials are not valid JSON: " + e.getMessage(), null);
        }

        String authBase = resolveAuthBaseUrl(creds);

        try {
            String token = obtainAccessToken(creds, authBase);
            if (token != null && !token.isBlank()) {
                return ValidationResult.success();
            }
            return ValidationResult.failure("AUTH_FAILED", "Could not obtain access token", null);
        } catch (Exception e) {
            return ValidationResult.failure("AUTH_FAILED", e.getMessage(), null);
        }
    }

    // ── Webhook signature verification ───────────────────────────────

    /**
     * Verifies the {@code X-KopoKopo-Signature} header against the request body
     * using HMAC-SHA256 with the API key as the secret.
     *
     * @param apiKey   the API key (shared secret)
     * @param body     the raw request body
     * @param signature the value of the X-KopoKopo-Signature header
     * @return true if the signature matches
     */
    public boolean verifyWebhookSignature(String apiKey, String body, String signature) {
        if (apiKey == null || body == null || signature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(apiKey.getBytes(), "HmacSHA256");
            mac.init(keySpec);
            byte[] computed = mac.doFinal(body.getBytes());
            // KopoKopo signature is hex-encoded
            StringBuilder sb = new StringBuilder();
            for (byte b : computed) {
                sb.append(String.format("%02x", b));
            }
            String computedHex = sb.toString();
            return computedHex.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("HMAC verification error", e);
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static boolean isProduction(Map<String, String> creds) {
        return "production".equalsIgnoreCase(creds.getOrDefault("environment", "sandbox"));
    }

    /** OAuth token endpoint host. */
    private String resolveAuthBaseUrl(Map<String, String> creds) {
        return isProduction(creds) ? PRODUCTION_AUTH_BASE : SANDBOX_BASE;
    }

    /** STK push and incoming-payment status host. */
    private String resolveApiBaseUrl(Map<String, String> creds) {
        return isProduction(creds) ? PRODUCTION_API_BASE : SANDBOX_BASE;
    }

    private String obtainAccessToken(Map<String, String> creds, String baseUrl) {
        String clientId = trimCred(creds.get("clientId"));
        String clientSecret = trimCred(creds.get("clientSecret"));

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientId and clientSecret are required");
        }

        String cacheKey = baseUrl + ":" + clientId;

        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.token;
        }

        try {
            HttpResponse<String> response = Unirest.post(baseUrl + OAUTH_PATH)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", USER_AGENT)
                    .field("client_id", clientId)
                    .field("client_secret", clientSecret)
                    .field("grant_type", "client_credentials")
                    .asString();

            if (response.getStatus() != 200) {
                String error = parseKopokopoError(response.getBody());
                throw new RuntimeException("OAuth failed: HTTP " + response.getStatus() + " — " + error);
            }

            var node = objectMapper.readTree(response.getBody());
            String token = node.get("access_token").asText();
            int expiresIn = node.has("expires_in") ? node.get("expires_in").asInt() : 3600;

            // Cache for slightly less than the full TTL
            Instant expiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 300, 60));
            tokenCache.put(cacheKey, new CachedToken(token, expiresAt));

            return token;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OAuth failed: " + e.getMessage(), e);
        }
    }

    private String extractIdFromLocation(String location) {
        if (location == null || location.isBlank()) return null;
        int lastSlash = location.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < location.length() - 1) {
            return location.substring(lastSlash + 1);
        }
        return location;
    }

    private static String trimCred(String value) {
        return value == null ? null : value.trim();
    }

    private String parseKopokopoError(String body) {
        if (body == null || body.isBlank()) return "Unknown error";
        try {
            var node = objectMapper.readTree(body);
            if (node.has("error_message")) return node.get("error_message").asText();
            if (node.has("error")) {
                String code = node.get("error").asText();
                if (node.has("error_description")) {
                    return code + " — " + node.get("error_description").asText();
                }
                return code;
            }
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ── Token cache entry ────────────────────────────────────────────

    private record CachedToken(String token, Instant expiresAt) {
    }
}
