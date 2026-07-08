package zelisline.ub.messaging.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import zelisline.ub.messaging.application.WhatsAppOutboundSender;

/**
 * Test endpoint for WhatsApp outbound messaging.
 * Only available in dev profiles — do NOT enable in production.
 */
@RestController
@RequestMapping("/api/v1/test/whatsapp")
public class WhatsAppTestController {

    private final WhatsAppOutboundSender sender;

    public WhatsAppTestController(WhatsAppOutboundSender sender) {
        this.sender = sender;
    }

    /**
     * Send a simple text message to a WhatsApp number.
     *
     * @param to   the recipient phone number in international format (e.g. 254712345678)
     * @param body the message text
     * @return 200 if sent, 400/500 otherwise
     */
    @PostMapping("/send")
    public ResponseEntity<String> sendText(
            @RequestParam String to,
            @RequestParam String body
    ) {
        boolean sent = sender.sendText(to, body);
        if (sent) {
            return ResponseEntity.ok("Message sent to " + to);
        }
        return ResponseEntity.status(500).body("Failed to send message — check logs and Meta config");
    }

    /**
     * Send a pre-approved template message.
     *
     * @param to           recipient phone number
     * @param templateName template name registered in Meta Business Manager
     * @param languageCode language code (e.g. "en", "en_US")
     */
    @PostMapping("/send-template")
    public ResponseEntity<String> sendTemplate(
            @RequestParam String to,
            @RequestParam String templateName,
            @RequestParam(defaultValue = "en") String languageCode
    ) {
        boolean sent = sender.sendTemplate(to, templateName, languageCode);
        if (sent) {
            return ResponseEntity.ok("Template message sent to " + to);
        }
        return ResponseEntity.status(500).body("Failed to send template — check template approval and Meta config");
    }
}
