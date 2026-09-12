package zelisline.ub.payments.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;

/**
 * Safaricom Daraja callbacks (STK + C2B). Paths are {@code permitAll}; authenticity
 * is established by matching {@code CheckoutRequestID} to a pending push.
 */
@RestController
@RequestMapping("/webhooks/daraja")
@RequiredArgsConstructor
public class DarajaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DarajaWebhookController.class);

    private final DarajaPaymentGateway darajaGateway;
    private final GatewayStkPushService gatewayStkPushService;

    @PostMapping("/stk")
    public ResponseEntity<String> stkCallback(HttpServletRequest request) {
        String rawBody = readRawBody(request);
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body("Empty body");
        }
        log.info("Daraja STK callback received: bytes={}", rawBody.length());
        WebhookResult result = darajaGateway.processWebhook(Map.of(), rawBody);
        gatewayStkPushService.processDarajaWebhook(result);
        return ResponseEntity.ok("Received");
    }

    /**
     * C2B validation URL — accept all (matching happens on confirmation / STK).
     * Register this URL on the Daraja portal for the platform Paybill.
     */
    @PostMapping("/c2b/validation")
    public ResponseEntity<Map<String, Object>> c2bValidation(HttpServletRequest request) {
        String raw = readRawBody(request);
        log.info("Daraja C2B validation: bytes={}", raw != null ? raw.length() : 0);
        return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
    }

    /**
     * C2B confirmation — Paybill/Till money-in. Matches pending STK by BillRefNumber,
     * else unique pending web order / remote grocery invoice, else unmatched inbound.
     */
    @PostMapping("/c2b/confirmation")
    public ResponseEntity<Map<String, Object>> c2bConfirmation(HttpServletRequest request) {
        String rawBody = readRawBody(request);
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
        }
        log.info("Daraja C2B confirmation: bytes={}", rawBody.length());
        WebhookResult result = darajaGateway.processWebhook(Map.of(), rawBody);
        gatewayStkPushService.processDarajaWebhook(result);
        return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
    }

    private static String readRawBody(HttpServletRequest request) {
        try {
            return new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
