package zelisline.ub.messaging.api;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelCredentialsService;
import zelisline.ub.messaging.application.WhatsAppInboundProcessor;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppWebhookSignatureVerifier;
import zelisline.ub.platform.logs.PlatformRequestLogErrorCapture;

/**
 * Meta WhatsApp Cloud API webhook endpoint.
 *
 * <p>Register in Meta Business Manager:
 * {@code {API_PUBLIC_BASE_URL}/webhooks/whatsapp}
 *
 * <p>Path {@code /webhooks/**} is {@code permitAll()} in SecurityConfig — verify-token
 * and HMAC checks happen here.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
public class MetaWhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final WhatsAppChannelCredentialsService credentialsService;
    private final WhatsAppInboundProcessor whatsAppInboundProcessor;

    public MetaWhatsAppWebhookController(
            WhatsAppChannelCredentialsService credentialsService,
            WhatsAppInboundProcessor whatsAppInboundProcessor
    ) {
        this.credentialsService = credentialsService;
        this.whatsAppInboundProcessor = whatsAppInboundProcessor;
    }

    @GetMapping
    public ResponseEntity<String> verifySubscription(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        if (!"subscribe".equalsIgnoreCase(mode)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid hub.mode");
        }
        if (!credentialsService.hasAnyVerifyToken()) {
            log.warn("Meta WhatsApp webhook verify rejected: webhook verify token not set");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Webhook verify token not configured");
        }
        if (!credentialsService.matchesVerifyToken(token)) {
            log.warn("Meta WhatsApp webhook verify rejected: token mismatch");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid verify token");
        }
        if (challenge == null || challenge.isBlank()) {
            return ResponseEntity.badRequest().body("Missing hub.challenge");
        }
        log.info("Meta WhatsApp webhook subscription verified");
        return ResponseEntity.ok(challenge);
    }

    @PostMapping
    public ResponseEntity<String> receiveEvent(HttpServletRequest request) {
        byte[] rawBody = readRawBody(request);
        if (rawBody == null || rawBody.length == 0) {
            log.warn("Meta WhatsApp webhook: empty body");
            return ResponseEntity.badRequest().body("Empty body");
        }

        // Verify the HMAC over the exact bytes Meta signed. Re-serialising the body (reading it
        // line by line, trimming, re-encoding) can silently change the bytes and turn a valid
        // signature into a 403, so the raw bytes are what is verified here.
        String signature = request.getHeader(SIGNATURE_HEADER);
        java.util.Set<String> appSecrets = credentialsService.candidateAppSecrets();
        if (!MetaWhatsAppWebhookSignatureVerifier.verifyAny(appSecrets, rawBody, signature)) {
            boolean headerAbsent = signature == null || signature.isBlank();
            String fingerprints = String.join(", ", credentialsService.candidateAppSecretFingerprints());
            String reason = headerAbsent
                    ? "Missing X-Hub-Signature-256 header — Meta did not sign this request, or a proxy stripped the header."
                    : "Signature mismatch: none of the " + appSecrets.size()
                            + " configured app secret(s) match X-Hub-Signature-256 (" + rawBody.length
                            + "-byte body). The request is signed by the app that owns this WABA — store that app's "
                            + "secret as the platform app secret, or on the number's own-credentials route. "
                            + "Configured: " + (fingerprints.isBlank() ? "none" : fingerprints) + ".";
            log.warn("Meta WhatsApp webhook rejected ({}, secretsTried={}, bodyBytes={}, configured=[{}])",
                    headerAbsent ? "signature header absent" : "signature mismatch",
                    appSecrets.size(), rawBody.length, fingerprints);
            // Surface the reason on the Super Admin → Platform → Logs row (else it reads "Forbidden").
            PlatformRequestLogErrorCapture.captureDetail("Meta WhatsApp webhook rejected", reason);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
        }
        if (appSecrets.isEmpty()) {
            log.warn("Meta WhatsApp webhook: no app secret configured — signature not verified");
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        try {
            whatsAppInboundProcessor.process(body);
        } catch (Exception ex) {
            log.error("Meta WhatsApp webhook: processor threw exception (still returning 200)", ex);
        }

        log.debug("Meta WhatsApp webhook payload: {}", truncate(body));
        log.info("Meta WhatsApp webhook received ({} bytes)", rawBody.length);
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    private static byte[] readRawBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (Exception ex) {
            log.error("Meta WhatsApp webhook: failed to read body", ex);
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) + "…" : value;
    }
}
