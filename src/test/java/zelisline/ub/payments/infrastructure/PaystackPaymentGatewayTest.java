package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.CheckoutRequest;
import zelisline.ub.payments.domain.spi.CheckoutResponse;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;

/**
 * Unit tests for {@link PaystackPaymentGateway} that need no network or Spring
 * context: signature verification, webhook parsing, credential validation
 * (prefix ↔ environment), and init rejections.
 */
class PaystackPaymentGatewayTest {

    private final PaystackPaymentGateway gateway = new PaystackPaymentGateway(new ObjectMapper());

    private static final String SECRET = "sk_test_0123456789abcdef";

    private static String hmacSha512Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Signature verification ───────────────────────────────────────

    @Test
    void verifiesValidSignature() throws Exception {
        String body = "{\"event\":\"charge.success\"}";
        String signature = hmacSha512Hex(SECRET, body);
        assertThat(gateway.verifyWebhookSignature(SECRET, body, signature)).isTrue();
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        String body = "{\"event\":\"charge.success\"}";
        String signature = hmacSha512Hex(SECRET, body);
        assertThat(gateway.verifyWebhookSignature(SECRET, body + " ", signature)).isFalse();
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        String body = "{\"event\":\"charge.success\"}";
        String signature = hmacSha512Hex(SECRET, body);
        assertThat(gateway.verifyWebhookSignature("sk_test_other", body, signature)).isFalse();
    }

    @Test
    void rejectsMalformedOrMissingSignature() throws Exception {
        String body = "{\"event\":\"charge.success\"}";
        assertThat(gateway.verifyWebhookSignature(SECRET, body, "not-hex")).isFalse();
        assertThat(gateway.verifyWebhookSignature(SECRET, body, null)).isFalse();
        assertThat(gateway.verifyWebhookSignature(null, body, "abcd")).isFalse();
    }

    // ── Webhook parsing ──────────────────────────────────────────────

    @Test
    void parsesChargeSuccessWebhook() {
        String payload = """
                {"event":"charge.success","data":{"id":123456,"reference":"pay_abc_123_x1y2z3","status":"success","amount":10000,"currency":"KES"}}
                """;
        WebhookResult result = gateway.processWebhook(Map.of(), payload);
        assertThat(result.success()).isTrue();
        assertThat(result.terminalFailure()).isFalse();
        assertThat(result.reference()).isEqualTo("pay_abc_123_x1y2z3");
        assertThat(result.webhookEventId()).isEqualTo("123456");
        assertThat(result.gatewayTransactionId()).isEqualTo("123456");
        assertThat(result.topic()).isEqualTo("charge.success");
        assertThat(result.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void parsesChargeFailedWebhook() {
        String payload = """
                {"event":"charge.failed","data":{"id":77,"reference":"pay_abc_124_q2w3e4","status":"failed","gateway_response":"Declined"}}
                """;
        WebhookResult result = gateway.processWebhook(Map.of(), payload);
        assertThat(result.success()).isFalse();
        assertThat(result.terminalFailure()).isTrue();
        assertThat(result.failureMessage()).isEqualTo("Declined");
    }

    @Test
    void parsesEmptyBodyAsEmptyResult() {
        WebhookResult result = gateway.processWebhook(Map.of(), "");
        assertThat(result.success()).isFalse();
        assertThat(result.rawPayload()).isEqualTo("");
    }

    // ── Credential validation (no network) ───────────────────────────

    @Test
    void validateConfigurationFailsOnMissingKeys() {
        PaymentGatewayConfig config = configWith(Map.of("environment", "sandbox"));
        ValidationResult result = gateway.validateConfiguration(config);
        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_KEYS");
    }

    @Test
    void keyEnvironmentRejectsMalformedPrefixes() {
        ValidationResult result = PaystackPaymentGateway.validateKeyEnvironment(Map.of(
                "environment", "sandbox",
                "publicKey", "pk_test_abc",
                "secretKey", "not_a_paystack_key"));
        assertThat(result).isNotNull();
        assertThat(result.errorCode()).isEqualTo("KEY_PREFIX");
    }

    @Test
    void keyEnvironmentAcceptsSandboxTestKeys() {
        ValidationResult result = PaystackPaymentGateway.validateKeyEnvironment(Map.of(
                "environment", "sandbox",
                "publicKey", "pk_test_abc",
                "secretKey", "sk_test_abc"));
        assertThat(result).isNull();
    }

    @Test
    void keyEnvironmentRejectsTestKeysInProduction() {
        ValidationResult result = PaystackPaymentGateway.validateKeyEnvironment(Map.of(
                "environment", "production",
                "publicKey", "pk_test_abc",
                "secretKey", "sk_test_abc"));
        assertThat(result).isNotNull();
        assertThat(result.errorCode()).isEqualTo("KEY_ENV_MISMATCH");
    }

    @Test
    void keyEnvironmentRejectsLiveKeysInSandbox() {
        ValidationResult result = PaystackPaymentGateway.validateKeyEnvironment(Map.of(
                "environment", "sandbox",
                "publicKey", "pk_live_abc",
                "secretKey", "sk_live_abc"));
        assertThat(result).isNotNull();
        assertThat(result.errorCode()).isEqualTo("KEY_ENV_MISMATCH");
    }

    // ── Init rejection paths (no network) ────────────────────────────

    @Test
    void initializeCheckoutRejectsWithoutSecretKey() {
        CheckoutResponse response = gateway.initializeCheckout(new CheckoutRequest(
                "b1", "c1", new java.math.BigDecimal("1500.00"), "KES",
                "customer@example.com", "pay_ref", "Order", "https://shop/checkout?order=x",
                Map.of(), Map.of()));
        assertThat(response.accepted()).isFalse();
        assertThat(response.responseCode()).isEqualTo("NO_SECRET_KEY");
    }

    @Test
    void initializeCheckoutRejectsWithoutEmail() {
        CheckoutResponse response = gateway.initializeCheckout(new CheckoutRequest(
                "b1", "c1", new java.math.BigDecimal("1500.00"), "KES",
                null, "pay_ref", "Order", "https://shop/checkout?order=x",
                Map.of(), Map.of("secretKey", SECRET)));
        assertThat(response.accepted()).isFalse();
        assertThat(response.responseCode()).isEqualTo("NO_EMAIL");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static PaymentGatewayConfig configWith(Map<String, String> creds) {
        PaymentGatewayConfig config = new PaymentGatewayConfig();
        config.setId("config-1");
        config.setBusinessId("business-1");
        config.setGatewayType(GatewayType.PAYSTACK);
        try {
            config.setCredentialsJson(new ObjectMapper().writeValueAsString(creds));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return config;
    }
}
