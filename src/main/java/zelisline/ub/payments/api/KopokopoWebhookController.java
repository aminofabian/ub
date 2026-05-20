package zelisline.ub.payments.api;

import java.io.BufferedReader;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Public webhook endpoint for KopoKopo payment notifications.
 *
 * <p>Path {@code /webhooks/**} is {@code permitAll()} in SecurityConfig —
 * HMAC signature verification is performed here, not in Spring Security.
 */
@RestController
@RequestMapping("/webhooks/kopokopo")
@RequiredArgsConstructor
public class KopokopoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(KopokopoWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-KopoKopo-Signature";

    private final KopokopoPaymentGateway kopokopoGateway;
    private final PaymentGatewayConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;
    private final GatewayStkPushService gatewayStkPushService;
    private final ObjectMapper objectMapper;

    @PostMapping("/payment")
    public ResponseEntity<String> receivePayment(HttpServletRequest request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        String rawBody = readBody(request);

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("KopoKopo webhook: empty body");
            return ResponseEntity.badRequest().body("Empty body");
        }

        log.info("KopoKopo webhook received: sig={}", signature != null ? "present" : "missing");

        // Find all ACTIVE KopoKopo configs across all businesses
        List<PaymentGatewayConfig> activeConfigs = configRepository
                .findByGatewayTypeAndStatus(GatewayType.KOPOKOPO, GatewayStatus.ACTIVE);

        if (activeConfigs.isEmpty()) {
            log.warn("KopoKopo webhook: no ACTIVE KopoKopo configs found");
            return ResponseEntity.ok("Received"); // ACK to avoid retries
        }

        // Try to match signature against any active config's API key
        PaymentGatewayConfig matchedConfig = null;
        for (PaymentGatewayConfig cfg : activeConfigs) {
            try {
                String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
                Map<String, String> creds = objectMapper.readValue(decrypted,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                String apiKey = creds.get("apiKey");
                if (apiKey != null && kopokopoGateway.verifyWebhookSignature(apiKey, rawBody, signature)) {
                    matchedConfig = cfg;
                    log.info("KopoKopo webhook: signature matched business={}", cfg.getBusinessId());
                    break;
                }
            } catch (Exception e) {
                log.debug("KopoKopo webhook: failed to verify config {}: {}", cfg.getId(), e.getMessage());
            }
        }

        if (matchedConfig == null) {
            log.warn("KopoKopo webhook: signature did not match any ACTIVE config");
            return ResponseEntity.ok("Received"); // ACK to avoid retries
        }

        // Parse the webhook payload
        WebhookResult result = kopokopoGateway.processWebhook(
                Map.of(SIGNATURE_HEADER, signature != null ? signature : ""), rawBody);

        log.info("KopoKopo webhook processed: businessId={} txnId={} amount={} success={}",
                matchedConfig.getBusinessId(), result.gatewayTransactionId(),
                result.amount(), result.success());

        gatewayStkPushService.processKopokopoWebhook(
                matchedConfig.getBusinessId(),
                matchedConfig.getId(),
                result);

        return ResponseEntity.ok("Received");
    }

    private String readBody(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String body = sb.toString().trim();
            return body.isEmpty() ? null : body;
        } catch (Exception e) {
            log.error("KopoKopo webhook: failed to read body", e);
            return null;
        }
    }
}
