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
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.PaystackPaymentGateway;
import zelisline.ub.payments.repository.GatewayCheckoutRepository;

/**
 * Public webhook endpoint for Paystack payment notifications.
 *
 * <p>Resolves {@code gateway_checkouts} by reference, then verifies HMAC with
 * either the tenant BYO secret or platform Kiosk Pay secret.
 */
@RestController
@RequestMapping("/webhooks/paystack")
@RequiredArgsConstructor
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);
    private static final String SIGNATURE_HEADER = "x-paystack-signature";

    private final PaystackPaymentGateway paystackGateway;
    private final GatewayCheckoutRepository checkoutRepository;
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

        Map<String, String> creds = checkoutService.resolvePaystackCredentialsForCheckout(checkout);
        String secretKey = creds != null ? creds.get("secretKey") : null;
        if (secretKey == null || secretKey.isBlank()
                || !paystackGateway.verifyWebhookSignature(secretKey, rawBody, signature)) {
            log.warn("Paystack webhook: signature mismatch business={} ref={} kioskPay={}",
                    checkout.getBusinessId(),
                    reference,
                    PlatformKioskPaySettings.PLATFORM_PAYSTACK_CONFIG_ID.equals(checkout.getConfigId()));
            return ResponseEntity.ok("Received");
        }

        WebhookResult parsed = paystackGateway.processWebhook(
                Map.of(SIGNATURE_HEADER, signature != null ? signature : ""), rawBody);
        String configId = checkout.getConfigId() != null
                ? checkout.getConfigId()
                : PlatformKioskPaySettings.PLATFORM_PAYSTACK_CONFIG_ID;
        checkoutService.handleWebhook(checkout.getBusinessId(), configId, parsed);
        return ResponseEntity.ok("Received");
    }

    private String extractReference(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String ref = root.path("data").path("reference").asText(null);
            return ref == null ? null : ref.trim();
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
