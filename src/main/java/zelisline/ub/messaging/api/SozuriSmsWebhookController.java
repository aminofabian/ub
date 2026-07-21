package zelisline.ub.messaging.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sozuri SMS callback endpoints (configure these URLs in the Sozuri project dashboard).
 *
 * <ul>
 *   <li>Inbox: {@code {API_PUBLIC_BASE_URL}/webhooks/sozuri/inbox}</li>
 *   <li>Delivery: {@code {API_PUBLIC_BASE_URL}/webhooks/sozuri/delivery}</li>
 * </ul>
 *
 * <p>Path {@code /webhooks/**} is {@code permitAll()} in SecurityConfig. Callbacks are
 * acknowledged with 200 so Sozuri does not retry endlessly; payloads are logged for now.
 */
@RestController
@RequestMapping("/webhooks/sozuri")
public class SozuriSmsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SozuriSmsWebhookController.class);

    @PostMapping("/inbox")
    public ResponseEntity<Void> inbox(HttpServletRequest request) {
        logCallback("inbox", request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/delivery")
    public ResponseEntity<Void> delivery(HttpServletRequest request) {
        logCallback("delivery", request);
        return ResponseEntity.ok().build();
    }

    private static void logCallback(String kind, HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            String contentType = request.getContentType();
            log.info(
                    "Sozuri SMS {} callback ({} bytes, contentType={}): {}",
                    kind,
                    bytes.length,
                    contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType,
                    truncate(body));
        } catch (IOException ex) {
            log.warn("Sozuri SMS {} callback: failed to read body: {}", kind, ex.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }
}
