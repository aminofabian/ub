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
        if (!isConfigured()) {
            throw new IllegalStateException("Resend is not configured");
        }
        String from = resolveFromAddress();
        if (from == null) {
            throw new IllegalStateException("Resend is not configured: set RESEND_FROM or RESEND_DOMAIN");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", toEmail);
        payload.put("subject", subject);
        payload.put("text", textBody);

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
        if (fromOverride != null && !fromOverride.isBlank()) {
            return fromOverride.trim();
        }
        if (domain != null && !domain.isBlank()) {
            return "UB <noreply@" + domain.trim() + ">";
        }
        return null;
    }
}
