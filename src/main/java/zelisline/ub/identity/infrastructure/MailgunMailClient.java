package zelisline.ub.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;

/**
 * Mailgun HTTP API (same shape as Mailgun samples: POST /v3/{domain}/messages,
 * {@code basicAuth("api", apiKey)}, {@code from}/{@code to}/{@code subject}/{@code text} as form fields).
 * <p>
 * Uses Kong {@code kong.unirest} (maintained Unirest); legacy {@code com.mashape.unirest} is unmaintained.
 */
@Service
public class MailgunMailClient {

    private static final String BASIC_AUTH_USER = "api";
    private static final String EU_REGION_KEY = "eu";
    private static final String US_API_BASE = "https://api.mailgun.net";
    private static final String EU_API_BASE = "https://api.eu.mailgun.net";

    @Value("${app.mailgun.private-api-key:}")
    private String privateApiKey;

    @Value("${app.mailgun.domain:}")
    private String domain;

    @Value("${app.mailgun.region:us}")
    private String region;

    @Value("${app.mailgun.from:}")
    private String fromOverride;

    public boolean isConfigured() {
        return privateApiKey != null
                && !privateApiKey.isBlank()
                && domain != null
                && !domain.isBlank();
    }

    /**
     * @throws UnirestException on transport errors
     * @throws IllegalStateException when Mailgun returns a non-2xx status
     */
    public void sendPlainText(String toEmail, String subject, String textBody) {
        if (!isConfigured()) {
            throw new IllegalStateException("Mailgun is not configured");
        }
        String base = EU_REGION_KEY.equalsIgnoreCase(region != null ? region.trim() : "") ? EU_API_BASE : US_API_BASE;
        String url = base + "/v3/" + domain + "/messages";

        HttpResponse<String> response = Unirest.post(url)
                .basicAuth(BASIC_AUTH_USER, privateApiKey.strip())
                .field("from", resolveFrom())
                .field("to", toEmail)
                .field("subject", subject)
                .field("text", textBody)
                .asString();

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(
                    "Mailgun HTTP " + response.getStatus() + ": " + response.getBody());
        }
    }

    private String resolveFrom() {
        if (fromOverride != null && !fromOverride.isBlank()) {
            return fromOverride.trim();
        }
        return "UB <noreply@" + domain + ">";
    }
}
