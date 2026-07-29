package zelisline.ub.payments.api;

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
import zelisline.ub.purchasing.application.SupplierDisbursementService;
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
    private final SupplierDisbursementService supplierDisbursementService;
    private final ObjectMapper objectMapper;

    @PostMapping("/payment")
    public ResponseEntity<String> receivePayment(HttpServletRequest request) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        String rawBody = readRawBody(request);

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("KopoKopo webhook: empty body");
            return ResponseEntity.badRequest().body("Empty body");
        }

        log.info("KopoKopo webhook received: sig={} bytes={}",
                signature != null ? "present" : "missing", rawBody.length());

        // Find all ACTIVE KopoKopo configs across all businesses
        List<PaymentGatewayConfig> activeConfigs = configRepository
                .findByGatewayTypeAndStatus(GatewayType.KOPOKOPO, GatewayStatus.ACTIVE);

        if (activeConfigs.isEmpty()) {
            log.warn("KopoKopo webhook: no ACTIVE KopoKopo configs found");
            return ResponseEntity.ok("Received"); // ACK to avoid retries
        }

        // Try to match signature against any active config's API key or client secret.
        // KopoKopo docs mention both; SDKs vary — accept either.
        PaymentGatewayConfig matchedConfig = null;
        for (PaymentGatewayConfig cfg : activeConfigs) {
            try {
                String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
                Map<String, String> creds = objectMapper.readValue(decrypted,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                String apiKey = creds.get("apiKey");
                String clientSecret = creds.get("clientSecret");
                boolean ok = (apiKey != null && kopokopoGateway.verifyWebhookSignature(apiKey, rawBody, signature))
                        || (clientSecret != null && kopokopoGateway.verifyWebhookSignature(clientSecret, rawBody, signature));
                if (ok) {
                    matchedConfig = cfg;
                    log.info("KopoKopo webhook: signature matched business={}", cfg.getBusinessId());
                    break;
                }
            } catch (Exception e) {
                log.debug("KopoKopo webhook: failed to verify config {}: {}", cfg.getId(), e.getMessage());
            }
        }

        if (matchedConfig == null) {
            log.warn("KopoKopo webhook: signature did not match any ACTIVE config (check apiKey/clientSecret)");
            return ResponseEntity.ok("Received"); // ACK to avoid retries
        }

        // Parse the webhook payload
        WebhookResult result = kopokopoGateway.processWebhook(
                Map.of(SIGNATURE_HEADER, signature != null ? signature : ""), rawBody);

        log.info("KopoKopo webhook processed: businessId={} topic={} txnId={} success={}",
                matchedConfig.getBusinessId(), result.topic(),
                result.gatewayTransactionId(), result.success());

        if ("send_money".equalsIgnoreCase(result.topic())) {
            supplierDisbursementService.processKopokopoSendMoneyWebhook(
                    matchedConfig.getBusinessId(),
                    matchedConfig.getId(),
                    result);
        } else {
            gatewayStkPushService.processKopokopoWebhook(
                    matchedConfig.getBusinessId(),
                    matchedConfig.getId(),
                    result);
        }

        return ResponseEntity.ok("Received");
    }

    /**
     * Raw body for HMAC — must match KopoKopo's signed bytes exactly.
     * Do not use {@link BufferedReader#readLine()} (alters newlines) or trim.
     */
    private String readRawBody(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            if (bytes.length == 0) {
                return null;
            }
            String encoding = request.getCharacterEncoding();
            if (encoding == null || encoding.isBlank()) {
                encoding = "UTF-8";
            }
            return new String(bytes, encoding);
        } catch (Exception e) {
            log.error("KopoKopo webhook: failed to read body", e);
            return null;
        }
    }
}
