package zelisline.ub.payments.api;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.GatewayCheckoutService;
import zelisline.ub.payments.domain.GatewayCheckout;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.PaystackPaymentGateway;
import zelisline.ub.payments.repository.GatewayCheckoutRepository;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Public webhook endpoint for Paystack payment notifications.
 *
 * <p>Path {@code /webhooks/**} is {@code permitAll()} in SecurityConfig —
 * signature verification is performed here, not in Spring Security.
 *
 * <p>Routing order (see scope doc §4.6): parse the payload for
 * {@code data.reference} → resolve the {@code gateway_checkouts} row → load
 * that tenant's config → verify {@code x-paystack-signature} with the
 * tenant's secret key (constant-time) → idempotent settle. We never
 * brute-force the HMAC across all ACTIVE configs.
 */
@RestController
@RequestMapping("/webhooks/paystack")
@RequiredArgsConstructor
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);
    private static final String SIGNATURE_HEADER = "x-paystack-signature";

    private final PaystackPaymentGateway paystackGateway;
    private final GatewayCheckoutRepository checkoutRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;
    private final GatewayCheckoutService checkoutService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<String> receive(HttpServletRequest request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        String rawBody = readRawBody(request);

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("Paystack webhook: empty body");
            return ResponseEntity.badRequest().body("Empty body");
        }

        log.info("Paystack webhook received: sig={} bytes={}",
                signature != null ? "present" : "missing", rawBody.length());

        String reference = extractReference(rawBody);
        if (reference == null || reference.isBlank()) {
            log.warn("Paystack webhook: no data.reference in payload");
            return ResponseEntity.ok("Received");
        }

        Optional<GatewayCheckout> checkoutOpt = checkoutRepository.findByReference(reference);
        if (checkoutOpt.isEmpty()) {
            log.warn("Paystack webhook: no matching checkout ref={}", reference);
            return ResponseEntity.ok("Received");
        }
        GatewayCheckout checkout = checkoutOpt.get();

        PaymentGatewayConfig cfg = configRepository.findById(checkout.getConfigId()).orElse(null);
        if (cfg == null || !checkout.getBusinessId().equals(cfg.getBusinessId())) {
            log.warn("Paystack webhook: config missing for checkout ref={}", reference);
            return ResponseEntity.ok("Received");
        }

        String secretKey;
        try {
            String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(decrypted, Map.class);
            secretKey = creds.get("secretKey");
        } catch (Exception e) {
            log.warn("Paystack webhook: cannot read credentials for config={}: {}",
                    cfg.getId(), e.getMessage());
            return ResponseEntity.ok("Received");
        }

        if (secretKey == null || secretKey.isBlank()
                || !paystackGateway.verifyWebhookSignature(secretKey, rawBody, signature)) {
            log.warn("Paystack webhook: signature mismatch business={} ref={}",
                    checkout.getBusinessId(), reference);
            return ResponseEntity.ok("Received"); // ACK to avoid provider retries
        }

        WebhookResult parsed = paystackGateway.processWebhook(
                Map.of(SIGNATURE_HEADER, signature != null ? signature : ""), rawBody);
        checkoutService.handleWebhook(checkout.getBusinessId(), cfg.getId(), parsed);
        return ResponseEntity.ok("Received");
    }

    private String extractReference(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String reference = root.path("data").path("reference").asText(null);
            return reference == null ? null : reference.trim();
        } catch (Exception e) {
            log.warn("Paystack webhook: cannot parse body for reference: {}", e.getMessage());
            return null;
        }
    }

    private static String readRawBody(HttpServletRequest request) {
        try {
            return new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
