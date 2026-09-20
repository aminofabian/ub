package zelisline.ub.payments.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PlatformCustodySettlementService;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.platform.logs.PlatformRequestLogErrorCapture;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;

/**
 * Safaricom Daraja callbacks (STK + C2B). Paths are {@code permitAll}; the payload
 * alone proves nothing (CheckoutRequestIDs and BillRefNumbers are known to callers),
 * so settlement decisions are verified server-side in {@link GatewayStkPushService}.
 * Set {@code app.payments.daraja.webhook-allowed-ips} to Safaricom's published M-Pesa
 * callback IPs in production — that is the origin-level control for C2B, which has no
 * query API to re-verify against.
 */
@RestController
@RequestMapping("/webhooks/daraja")
@RequiredArgsConstructor
public class DarajaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DarajaWebhookController.class);

    private final DarajaPaymentGateway darajaGateway;
    private final GatewayStkPushService gatewayStkPushService;
    private final ObjectProvider<PlatformCustodySettlementService> platformCustodySettlementService;

    @Value("${app.payments.daraja.webhook-allowed-ips:}")
    private String webhookAllowedIps;

    @PostMapping("/stk")
    public ResponseEntity<String> stkCallback(HttpServletRequest request) {
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
        }
        String rawBody = readRawBody(request);
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body("Empty body");
        }
        log.info("Daraja STK callback received: bytes={}", rawBody.length());
        WebhookResult result = darajaGateway.processWebhook(Map.of(), rawBody);
        recordCallbackFailure("mpesa/stk-callback", result,
                darajaGateway.stkCallbackResultCode(rawBody));
        gatewayStkPushService.processDarajaWebhook(result);
        return ResponseEntity.ok("Received");
    }

    /**
     * C2B validation URL — accept all (matching happens on confirmation / STK).
     * Register this URL on the Daraja portal for the platform Paybill.
     */
    @PostMapping("/c2b/validation")
    public ResponseEntity<Map<String, Object>> c2bValidation(HttpServletRequest request) {
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ResultCode", 1, "ResultDesc", "Rejected"));
        }
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
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ResultCode", 1, "ResultDesc", "Rejected"));
        }
        String rawBody = readRawBody(request);
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
        }
        log.info("Daraja C2B confirmation: bytes={}", rawBody.length());
        WebhookResult result = darajaGateway.processWebhook(Map.of(), rawBody);
        recordCallbackFailure("mpesa/c2b-confirmation", result, null);
        gatewayStkPushService.processDarajaWebhook(result);
        return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
    }

    /**
     * Daraja B2B result — custody settle to a tenant paybill/till. Matched by ConversationID.
     */
    @PostMapping("/b2b/result")
    public ResponseEntity<Map<String, Object>> b2bResult(HttpServletRequest request) {
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ResultCode", 1, "ResultDesc", "Rejected"));
        }
        String rawBody = readRawBody(request);
        if (rawBody != null && !rawBody.isBlank()) {
            log.info("Daraja B2B result: bytes={}", rawBody.length());
            handleB2b(rawBody);
        }
        return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
    }

    /** Daraja B2B queue timeout — resolve the settlement as failed for ops retry. */
    @PostMapping("/b2b/timeout")
    public ResponseEntity<Map<String, Object>> b2bTimeout(HttpServletRequest request) {
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ResultCode", 1, "ResultDesc", "Rejected"));
        }
        String rawBody = readRawBody(request);
        if (rawBody != null && !rawBody.isBlank()) {
            log.warn("Daraja B2B timeout: bytes={}", rawBody.length());
            handleB2b(rawBody);
        }
        return ResponseEntity.ok(DarajaPaymentGateway.c2bAck(0, "Accepted"));
    }

    /**
     * Surface a declined Safaricom callback on Super Admin → Platform → Logs.
     *
     * <p>Safaricom retries a callback it does not get a 200 for, so every one of these is
     * acknowledged as OK regardless of the ResultCode inside. That makes a declined payment
     * indistinguishable from a successful one in the request log unless the failure is
     * recorded explicitly.
     *
     * <p>Also surfaces ResultDesc "Wrong credentials" even when the ResultCode (e.g. 4999)
     * is classified as still-pending — the payment stays open, but ops can see the passkey
     * problem.
     */
    private static void recordCallbackFailure(String type, WebhookResult result, String resultCode) {
        if (result == null) {
            return;
        }
        boolean credentialHint = DarajaPaymentGateway.looksLikeWrongCredentials(result.failureMessage());
        if (!result.terminalFailure() && !credentialHint) {
            return;
        }
        String desc = result.failureMessage() == null || result.failureMessage().isBlank()
                ? "Declined by Safaricom"
                : result.failureMessage();
        String title = resultCode == null || resultCode.isBlank()
                ? "M-Pesa declined: " + desc
                : "M-Pesa ResultCode " + resultCode + ": " + desc;

        StringBuilder detail = new StringBuilder(desc);
        if (resultCode != null && !resultCode.isBlank()) {
            detail.append("\nResultCode: ").append(resultCode);
        }
        if (credentialHint && !result.terminalFailure()) {
            detail.append("\n\nThis ResultCode is treated as still-pending for the payment, ")
                    .append("but ResultDesc says Wrong credentials — check the Lipa Na M-Pesa ")
                    .append("passkey matches the Go Live shortcode in Super Admin → Platform → Daraja.");
        }
        if (result.gatewayCheckoutId() != null) {
            detail.append("\nCheckoutRequestID: ").append(result.gatewayCheckoutId());
        }
        if (result.reference() != null) {
            detail.append("\nMerchantRequestID: ").append(result.reference());
        }
        if (result.rawPayload() != null) {
            detail.append("\n\nCallback payload:\n").append(result.rawPayload());
        }

        log.warn("Safaricom callback declined type={} resultCode={} checkoutId={} desc={}",
                type, resultCode, result.gatewayCheckoutId(), desc);
        PlatformRequestLogErrorCapture.captureUpstreamFailure(type, title, detail.toString());
    }

    private void handleB2b(String rawBody) {
        PlatformCustodySettlementService custody = platformCustodySettlementService.getIfAvailable();
        if (custody == null) {
            return;
        }
        custody.handleDarajaDisburseResult(darajaGateway.parseB2BResult(rawBody));
    }

    /**
     * Optional Safaricom origin allowlist (comma-separated IPs). Empty = accept from
     * any origin; payload-level verification in the service still applies.
     */
    private boolean originAllowed(HttpServletRequest request) {
        if (webhookAllowedIps == null || webhookAllowedIps.isBlank()) {
            return true;
        }
        String ip = clientIp(request);
        for (String allowed : webhookAllowedIps.split(",")) {
            String candidate = allowed.trim();
            if (!candidate.isEmpty() && candidate.equals(ip)) {
                return true;
            }
        }
        log.warn("Daraja webhook rejected from non-allowlisted IP: {}", ip);
        return false;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String readRawBody(HttpServletRequest request) {
        try {
            return new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
