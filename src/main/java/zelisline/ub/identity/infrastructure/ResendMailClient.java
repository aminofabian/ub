package zelisline.ub.identity.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

/**
 * Resend HTTP API (<a href="https://resend.com/docs/api-reference/emails/send-email">send email</a>):
 * {@code POST https://api.resend.com/emails} with Bearer token and JSON body.
 */
@Service
@RequiredArgsConstructor
public class ResendMailClient {

    private static final String API_URL = "https://api.resend.com/emails";

    private final ObjectMapper objectMapper;

    @Value("${app.resend.api-key:}")
    private String apiKey;

    @Value("${app.resend.from:}")
    private String fromOverride;

    @Value("${app.resend.domain:}")
    private String domain;

    public boolean isConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return resolveFromAddress() != null;
    }

    /** @throws IllegalStateException when Resend returns a non-2xx status or JSON serialization fails */
    public void sendPlainText(String toEmail, String subject, String textBody) {
        sendPlainText(toEmail, subject, textBody, null);
    }

    public void sendPlainText(String toEmail, String subject, String textBody, String fromDisplayName) {
        Map<String, Object> payload = buildBasePayload(toEmail, subject, fromDisplayName);
        payload.put("text", textBody);
        doSend(payload);
    }

    /** @throws IllegalStateException when Resend returns a non-2xx status or JSON serialization fails */
    public void sendHtml(String toEmail, String subject, String htmlBody) {
        sendHtml(toEmail, subject, htmlBody, null);
    }

    public void sendHtml(String toEmail, String subject, String htmlBody, String fromDisplayName) {
        Map<String, Object> payload = buildBasePayload(toEmail, subject, fromDisplayName);
        payload.put("html", htmlBody);
        doSend(payload);
    }

    private Map<String, Object> buildBasePayload(String toEmail, String subject, String fromDisplayName) {
        if (!isConfigured()) {
            throw new IllegalStateException("Resend is not configured");
        }
        String from = resolveFromAddress(fromDisplayName);
        if (from == null) {
            throw new IllegalStateException("Resend is not configured: set RESEND_FROM or RESEND_DOMAIN");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", toEmail);
        payload.put("subject", subject);
        return payload;
    }

    private void doSend(Map<String, Object> payload) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Resend request body", e);
        }

        HttpResponse<String> response = Unirest.post(API_URL)
                .header("Authorization", "Bearer " + apiKey.strip())
                .header("Content-Type", "application/json")
                .body(json)
                .asString();

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(
                    "Resend HTTP " + response.getStatus() + ": " + response.getBody());
        }
    }

    private String resolveFromAddress() {
        return resolveFromAddress(null);
    }

    /**
     * Builds the Resend {@code from} value. When {@code fromDisplayName} is set, it replaces
     * the display-name portion of {@code RESEND_FROM} / the default {@code UB <noreply@…>} so
     * tenant emails show e.g. {@code Palmart <noreply@palmart.co.ke>}.
     */
    private String resolveFromAddress(String fromDisplayName) {
        String base;
        if (fromOverride != null && !fromOverride.isBlank()) {
            base = fromOverride.trim();
        } else if (domain != null && !domain.isBlank()) {
            base = "UB <noreply@" + domain.trim() + ">";
        } else {
            return null;
        }
        return withDisplayName(base, fromDisplayName);
    }

    /** Replace or attach a display name on a From address like {@code Name <a@b>} or bare email. */
    static String withDisplayName(String from, String displayName) {
        if (from == null || from.isBlank()) {
            return from;
        }
        if (displayName == null || displayName.isBlank()) {
            return from.trim();
        }
        String safe = sanitizeDisplayName(displayName);
        if (safe.isEmpty()) {
            return from.trim();
        }
        String trimmed = from.trim();
        int lt = trimmed.indexOf('<');
        int gt = trimmed.indexOf('>');
        if (lt >= 0 && gt > lt) {
            return safe + " " + trimmed.substring(lt, gt + 1);
        }
        return safe + " <" + trimmed + ">";
    }

    static String sanitizeDisplayName(String displayName) {
        return displayName
                .replace("<", "")
                .replace(">", "")
                .replace("\"", "")
                .replace("\r", "")
                .replace("\n", "")
                .strip();
    }
}
