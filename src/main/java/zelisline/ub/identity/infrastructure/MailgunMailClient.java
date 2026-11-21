package zelisline.ub.identity.infrastructure;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kong.unirest.ContentType;
import kong.unirest.HttpResponse;
import kong.unirest.MultipartBody;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import zelisline.ub.identity.application.MailAttachment;

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
        sendPlainText(toEmail, subject, textBody, null);
    }

    public void sendPlainText(String toEmail, String subject, String textBody, String fromDisplayName) {
        doSend(toEmail, subject, "text", textBody, fromDisplayName);
    }

    /**
     * @throws UnirestException on transport errors
     * @throws IllegalStateException when Mailgun returns a non-2xx status
     */
    public void sendHtml(String toEmail, String subject, String htmlBody) {
        sendHtml(toEmail, subject, htmlBody, null);
    }

    public void sendHtml(String toEmail, String subject, String htmlBody, String fromDisplayName) {
        doSend(toEmail, subject, "html", htmlBody, fromDisplayName);
    }

    public void sendHtml(
            String toEmail,
            String subject,
            String htmlBody,
            String textBody,
            String fromDisplayName,
            List<MailAttachment> attachments
    ) {
        if (!isConfigured()) {
            throw new IllegalStateException("Mailgun is not configured");
        }
        MultipartBody request = Unirest.post(messagesUrl())
                .basicAuth(BASIC_AUTH_USER, privateApiKey.strip())
                .field("from", resolveFrom(fromDisplayName))
                .field("to", toEmail)
                .field("subject", subject)
                .field("html", htmlBody);
        if (textBody != null && !textBody.isBlank()) {
            request = request.field("text", textBody);
        }
        if (attachments != null) {
            for (MailAttachment file : attachments) {
                if (file.content().length == 0) {
                    continue;
                }
                request = request.field(
                        "attachment",
                        new ByteArrayInputStream(file.content()),
                        ContentType.APPLICATION_OCTET_STREAM,
                        file.filename());
            }
        }
        HttpResponse<String> response = request.asString();
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(
                    "Mailgun HTTP " + response.getStatus() + ": " + response.getBody());
        }
    }

    private void doSend(
            String toEmail, String subject, String bodyFieldName, String body, String fromDisplayName) {
        if (!isConfigured()) {
            throw new IllegalStateException("Mailgun is not configured");
        }
        HttpResponse<String> response = Unirest.post(messagesUrl())
                .basicAuth(BASIC_AUTH_USER, privateApiKey.strip())
                .field("from", resolveFrom(fromDisplayName))
                .field("to", toEmail)
                .field("subject", subject)
                .field(bodyFieldName, body)
                .asString();

        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(
                    "Mailgun HTTP " + response.getStatus() + ": " + response.getBody());
        }
    }

    private String messagesUrl() {
        String base = EU_REGION_KEY.equalsIgnoreCase(region != null ? region.trim() : "") ? EU_API_BASE : US_API_BASE;
        return base + "/v3/" + domain + "/messages";
    }

    private String resolveFrom(String fromDisplayName) {
        String base;
        if (fromOverride != null && !fromOverride.isBlank()) {
            base = fromOverride.trim();
        } else {
            base = "UB <noreply@" + domain + ">";
        }
        return ResendMailClient.withDisplayName(base, fromDisplayName);
    }
}
