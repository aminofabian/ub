package zelisline.ub.messaging.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
import zelisline.ub.credits.sms.application.SmsInboundService;

/**
 * Sozuri SMS callbacks. Configure in the Sozuri dashboard:
 * <ul>
 *   <li>Inbox: {@code {API_PUBLIC_BASE_URL}/webhooks/sozuri/inbox}</li>
 *   <li>Delivery: {@code {API_PUBLIC_BASE_URL}/webhooks/sozuri/delivery}</li>
 * </ul>
 * Delivery reports update the per-message log; an inbound STOP / JOIN changes the
 * sender's marketing consent (see {@link SmsInboundService}). Public and unauthenticated:
 * the body is size-bounded, and only field names are logged because the values carry
 * phone numbers and message text.
 */
@RestController
@RequestMapping("/webhooks/sozuri")
@RequiredArgsConstructor
public class SozuriSmsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SozuriSmsWebhookController.class);
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final SmsInboundService smsInboundService;
    private final ObjectMapper objectMapper;

    @PostMapping("/inbox")
    public ResponseEntity<Void> inbox(HttpServletRequest request) {
        Map<String, String> fields = readFields(request);
        try {
            int changed = smsInboundService.handleInbound(fields);
            log.info("Sozuri SMS inbox callback fields={} consent_changes={}", fields.keySet(), changed);
        } catch (RuntimeException ex) {
            log.warn("Sozuri SMS inbox callback failed: {}", ex.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/delivery")
    public ResponseEntity<Void> delivery(HttpServletRequest request) {
        Map<String, String> fields = readFields(request);
        try {
            int updated = smsInboundService.handleDelivery(fields);
            log.info("Sozuri SMS delivery callback fields={} rows_updated={}", fields.keySet(), updated);
        } catch (RuntimeException ex) {
            log.warn("Sozuri SMS delivery callback failed: {}", ex.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Flattens a JSON object body (one level of nesting) or form parameters into a map
     * with lower-cased keys. Oversized or unreadable bodies yield an empty map.
     */
    private Map<String, String> readFields(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0 && values[0] != null) {
                out.put(key.toLowerCase(Locale.ROOT), values[0]);
            }
        });
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
                return out;
            }
            JsonNode root = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (root != null && root.isArray() && !root.isEmpty()) {
                root = root.get(0);
            }
            if (root != null && root.isObject()) {
                flatten(root, out, 0);
            }
        } catch (IOException | RuntimeException ex) {
            // Form-encoded or malformed body: parameters above are all we have.
        }
        return out;
    }

    private static void flatten(JsonNode node, Map<String, String> out, int depth) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                out.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), value.asText(""));
            } else if (value.isObject() && depth < 1) {
                flatten(value, out, depth + 1);
            }
        }
    }
}
