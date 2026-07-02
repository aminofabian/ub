package zelisline.ub.messaging.api;

import java.io.BufferedReader;

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
import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.config.MessagingProperties;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppWebhookSignatureVerifier;

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
@RequiredArgsConstructor
public class MetaWhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final MessagingProperties messagingProperties;

    @GetMapping
    public ResponseEntity<String> verifySubscription(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        String configuredToken = messagingProperties.metaWhatsApp().webhookVerifyToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("Meta WhatsApp webhook verify rejected: WHATSAPP_META_WEBHOOK_VERIFY_TOKEN not set");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Webhook verify token not configured");
        }
        if (!"subscribe".equalsIgnoreCase(mode)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid hub.mode");
        }
        if (!configuredToken.equals(token)) {
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
        String rawBody = readBody(request);
        if (rawBody == null || rawBody.isBlank()) {
            log.warn("Meta WhatsApp webhook: empty body");
            return ResponseEntity.badRequest().body("Empty body");
        }

        String appSecret = messagingProperties.metaWhatsApp().appSecret();
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (!MetaWhatsAppWebhookSignatureVerifier.verify(appSecret, rawBody, signature)) {
            log.warn("Meta WhatsApp webhook: invalid signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
        }
        if (appSecret == null || appSecret.isBlank()) {
            log.warn("Meta WhatsApp webhook: WHATSAPP_META_APP_SECRET not set — signature not verified");
        }

        log.debug("Meta WhatsApp webhook payload: {}", truncate(rawBody));
        log.info("Meta WhatsApp webhook received ({} bytes)", rawBody.length());
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    private static String readBody(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String body = sb.toString().trim();
            return body.isEmpty() ? null : body;
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
