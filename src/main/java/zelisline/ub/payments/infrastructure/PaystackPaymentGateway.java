package zelisline.ub.payments.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.CheckoutPaymentGateway;
import zelisline.ub.payments.domain.spi.CheckoutRequest;
import zelisline.ub.payments.domain.spi.CheckoutResponse;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.domain.spi.StkStatusResponse;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.VerifyTransactionRequest;
import zelisline.ub.payments.domain.spi.VerifyTransactionResponse;
import zelisline.ub.payments.domain.spi.WebhookResult;

/**
 * Paystack hosted-checkout provider (card, bank, Paystack mobile money).
 *
 * <p>Tenant-owned credentials only: every call uses the decrypted secret key
 * from the business's {@link PaymentGatewayConfig}. The API host is the same
 * for test and live; the environment only determines which key pairs are
 * valid (prefixes are validated in {@link #validateConfiguration}).
 *
 * <p>STK/till methods are intentionally unsupported — phone-prompt M-Pesa
 * stays on KopoKopo (see the Paystack scope doc §4.4).
 */
@Component
public class PaystackPaymentGateway implements CheckoutPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaystackPaymentGateway.class);

    private static final String API_BASE = "https://api.paystack.co";
    private static final String INITIALIZE_PATH = "/transaction/initialize";
    private static final String VERIFY_PATH = "/transaction/verify";
    /** Lightweight non-mutating auth + account info check (returns account currency). */
    private static final String VALIDATE_PATH = "/integration/payment_session_timeout";
    private static final String USER_AGENT = "Kiosk.ke/1.0 Paystack";

    private final ObjectMapper objectMapper;

    public PaystackPaymentGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String gatewayType() {
        return GatewayType.PAYSTACK.name();
    }

    // ── Checkout (initialize / verify) ───────────────────────────────

    @Override
    public CheckoutResponse initializeCheckout(CheckoutRequest request) {
        Map<String, String> creds = request.credentials();
        String secretKey = creds.get("secretKey");
        if (secretKey == null || secretKey.isBlank()) {
            return CheckoutResponse.rejected("NO_SECRET_KEY", "secretKey is required in credentials", null);
        }
        if (request.reference() == null || request.reference().isBlank()) {
            return CheckoutResponse.rejected("NO_REFERENCE", "reference is required", null);
        }
        if (request.email() == null || request.email().isBlank()) {
            return CheckoutResponse.rejected("NO_EMAIL", "customer email is required", null);
        }

        // Domain amounts are decimal (KES); Paystack wants minor units (cents/kobo).
        long minorUnits = request.amount()
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", minorUnits);
        body.put("email", request.email());
        body.put("currency", request.currency() != null && !request.currency().isBlank()
                ? request.currency()
                : "KES");
        body.put("reference", request.reference());
        body.put("callback_url", request.callbackUrl());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        if (request.description() != null && !request.description().isBlank()) {
            metadata.put("description", request.description());
        }
        if (!metadata.isEmpty()) {
            body.put("metadata", metadata);
        }

        try {
            HttpResponse<String> response = Unirest.post(API_BASE + INITIALIZE_PATH)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .body(objectMapper.writeValueAsString(body))
                    .asString();

            if (response.getStatus() == 200) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.path("status").asBoolean(false)) {
                    JsonNode data = root.path("data");
                    String authorizationUrl = textOrNull(data, "authorization_url");
                    String accessCode = textOrNull(data, "access_code");
                    String reference = textOrNull(data, "reference");
                    String providerTxnId = data.has("id") && data.get("id").isNumber()
                            ? String.valueOf(data.get("id").asLong())
                            : null;
                    log.info("Paystack checkout initialized: ref={} accessCode={}", reference, accessCode);
                    return CheckoutResponse.accepted(
                            reference != null ? reference : request.reference(),
                            authorizationUrl,
                            accessCode,
                            providerTxnId,
                            "PENDING",
                            response.getBody());
                }
                return CheckoutResponse.rejected(
                        "INIT_FAILED",
                        root.path("message").asText("Could not initialize payment"),
                        response.getBody());
            }

            log.warn("Paystack initialize rejected: status={} body={}", response.getStatus(), response.getBody());
            return CheckoutResponse.rejected(
                    String.valueOf(response.getStatus()),
                    parseError(response.getBody()),
                    response.getBody());
        } catch (Exception e) {
            log.error("Paystack initialize failed", e);
            return CheckoutResponse.rejected("NETWORK_ERROR", e.getMessage(), null);
        }
    }

    @Override
    public VerifyTransactionResponse verifyTransaction(VerifyTransactionRequest request) {
        Map<String, String> creds = request.credentials();
        String secretKey = creds.get("secretKey");
        if (secretKey == null || secretKey.isBlank()) {
            return VerifyTransactionResponse.failed("NO_CREDENTIALS", "secretKey is required", null);
        }
        String encodedReference = URLEncoder.encode(request.reference(), StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = Unirest.get(API_BASE + VERIFY_PATH + "/" + encodedReference)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();

            if (response.getStatus() != 200) {
                log.warn("Paystack verify failed: ref={} status={}", request.reference(), response.getStatus());
                return VerifyTransactionResponse.failed(
                        String.valueOf(response.getStatus()),
                        parseError(response.getBody()),
                        response.getBody());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            String providerStatus = textOrNull(data, "status");
            String providerTxnId = data.has("id") && data.get("id").isNumber()
                    ? String.valueOf(data.get("id").asLong())
                    : null;
            BigDecimal amount = null;
            if (data.has("amount") && data.get("amount").isNumber()) {
                amount = BigDecimal.valueOf(data.get("amount").asLong(), 2);
            }
            String currency = textOrNull(data, "currency");
            BigDecimal providerFee = null;
            if (data.has("fees") && data.get("fees").isNumber()) {
                providerFee = BigDecimal.valueOf(data.get("fees").asLong(), 2);
            }

            if ("success".equals(providerStatus)) {
                return new VerifyTransactionResponse(
                        true, false, false, providerStatus, providerTxnId,
                        request.reference(), amount, currency, null, response.getBody(), providerFee);
            }
            if ("failed".equals(providerStatus) || "abandoned".equals(providerStatus)) {
                String failure = firstNonBlank(
                        textOrNull(data, "gateway_response"),
                        textOrNull(data, "failure_message"),
                        "Payment " + providerStatus);
                return new VerifyTransactionResponse(
                        false, true, false, providerStatus, providerTxnId,
                        request.reference(), amount, currency, failure, response.getBody(), providerFee);
            }
            return VerifyTransactionResponse.pending(providerStatus, response.getBody());
        } catch (Exception e) {
            log.error("Paystack verify failed for ref={}", request.reference(), e);
            return VerifyTransactionResponse.failed("NETWORK_ERROR", e.getMessage(), null);
        }
    }

    // ── Webhooks ─────────────────────────────────────────────────────

    /**
     * Verifies the {@code x-paystack-signature} header: HMAC-SHA512 of the raw
     * body with the tenant's secret key, hex-encoded, constant-time compare.
     */
    public boolean verifyWebhookSignature(String secretKey, String rawBody, String signature) {
        if (secretKey == null || rawBody == null || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            mac.init(keySpec);
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(signature.trim());
            } catch (IllegalArgumentException e) {
                return false;
            }
            return MessageDigest.isEqual(computed, provided);
        } catch (Exception e) {
            log.error("Paystack webhook signature verification error", e);
            return false;
        }
    }

    @Override
    public WebhookResult processWebhook(Map<String, String> headers, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return WebhookResult.empty(rawBody);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String event = root.path("event").asText(null);
            JsonNode data = root.path("data");
            String reference = textOrNull(data, "reference");
            String providerTxnId = data.has("id") && data.get("id").isNumber()
                    ? String.valueOf(data.get("id").asLong())
                    : textOrNull(data, "id");
            boolean success = "charge.success".equals(event);
            boolean terminalFailure = "charge.failed".equals(event) || "charge.unknown".equals(event);
            String failureMessage = null;
            if (!success) {
                failureMessage = firstNonBlank(
                        textOrNull(data, "gateway_response"),
                        textOrNull(data, "failure_message"),
                        textOrNull(data, "message"));
            }
            BigDecimal amount = null;
            if (data.has("amount") && data.get("amount").isNumber()) {
                amount = BigDecimal.valueOf(data.get("amount").asLong(), 2);
            }
            return new WebhookResult(
                    null, providerTxnId, null, amount, reference, success, terminalFailure,
                    null, providerTxnId, event, rawBody, failureMessage);
        } catch (Exception e) {
            log.warn("Paystack webhook parse failed: {}", e.getMessage());
            return WebhookResult.empty(rawBody);
        }
    }

    // ── STK / display (unsupported for Paystack v1) ──────────────────

    @Override
    public StkPushResponse initiateStkPush(StkPushRequest request) {
        return StkPushResponse.rejected(
                "UNSUPPORTED",
                "Paystack does not support M-Pesa phone prompts — use KopoKopo for STK Push.");
    }

    @Override
    public StkStatusResponse queryStkStatus(String gatewayCheckoutRequestId) {
        return new StkStatusResponse(
                "UNSUPPORTED", "Paystack does not support STK status queries", false, true, null, null);
    }

    @Override
    public DisplayInstructions getDisplayInstructions(String businessId) {
        return null;
    }

    // ── Test connection ──────────────────────────────────────────────

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
        } catch (Exception e) {
            return ValidationResult.failure("INVALID_JSON", "Credentials are not valid JSON: " + e.getMessage(), null);
        }

        String secretKey = creds.get("secretKey");
        String publicKey = creds.get("publicKey");
        if (secretKey == null || secretKey.isBlank() || publicKey == null || publicKey.isBlank()) {
            return ValidationResult.failure("MISSING_KEYS", "Both secretKey and publicKey are required", null);
        }

        String env = creds.getOrDefault("environment", "sandbox");
        boolean production = "production".equals(env);
        String envLabel = production ? "Production" : "Sandbox";

        ValidationResult keyEnv = validateKeyEnvironment(creds);
        if (keyEnv != null) {
            return keyEnv;
        }

        try {
            HttpResponse<String> response = Unirest.get(API_BASE + VALIDATE_PATH)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .asString();
            if (response.getStatus() == 200) {
                return new ValidationResult(true, null, null, response.getBody());
            }
            return ValidationResult.failure("AUTH_FAILED",
                    "[" + envLabel + "] " + parseError(response.getBody()), response.getBody());
        } catch (Exception e) {
            return ValidationResult.failure("AUTH_FAILED",
                    "[" + envLabel + "] " + e.getMessage(), null);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Enforces the "no mixing keys" rule: key prefixes must match the stored
     * environment. Returns {@code null} when the pair is consistent.
     */
    public static ValidationResult validateKeyEnvironment(Map<String, String> creds) {
        String env = creds.getOrDefault("environment", "sandbox");
        boolean production = "production".equalsIgnoreCase(env);
        String envLabel = production ? "Production" : "Sandbox";
        String secretKey = creds.get("secretKey");
        String publicKey = creds.get("publicKey");

        if (secretKey != null && !secretKey.isBlank()
                && !secretKey.startsWith("sk_test_") && !secretKey.startsWith("sk_live_")) {
            return ValidationResult.failure("KEY_PREFIX",
                    "Secret key must start with sk_test_ or sk_live_", null);
        }
        if (publicKey != null && !publicKey.isBlank()
                && !publicKey.startsWith("pk_test_") && !publicKey.startsWith("pk_live_")) {
            return ValidationResult.failure("KEY_PREFIX",
                    "Public key must start with pk_test_ or pk_live_", null);
        }

        if (production && ((secretKey != null && secretKey.startsWith("sk_test_"))
                || (publicKey != null && publicKey.startsWith("pk_test_")))) {
            return ValidationResult.failure("KEY_ENV_MISMATCH",
                    "[" + envLabel + "] Test keys cannot be used in Production — use sk_live_ / pk_live_ keys.", null);
        }
        if (!production && ((secretKey != null && secretKey.startsWith("sk_live_"))
                || (publicKey != null && publicKey.startsWith("pk_live_")))) {
            return ValidationResult.failure("KEY_ENV_MISMATCH",
                    "[" + envLabel + "] Live keys cannot be used in Sandbox — use sk_test_ / pk_test_ keys.", null);
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String parseError(String body) {
        if (body == null || body.isBlank()) {
            return "Paystack request failed";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
