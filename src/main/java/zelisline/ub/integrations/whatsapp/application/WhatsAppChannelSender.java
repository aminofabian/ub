package zelisline.ub.integrations.whatsapp.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Sends WhatsApp messages (text + template) using the <b>platform</b> Meta credentials
 * (super-admin).
 *
 * <p>Deliberately independent of the {@code messaging} package (which owns tenant-scoped
 * credit templates) so the dependency graph stays acyclic:
 * {@code messaging -> crm -> integrations.whatsapp -> platform}. The request shape mirrors
 * {@code MetaWhatsAppMessagingClient} ({@code messaging_product} + text/template body).
 *
 * <p>See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.5 and {@code M4-PLAN.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppChannelSender {

    /**
     * Outcome of one send attempt. {@code whatsappMessageId} is Meta's wamid (present on
     * success) — persisted so delivery-status callbacks can be correlated later.
     */
    public record SendResult(boolean sent, String whatsappMessageId, String channel, String detail) {

        static SendResult ok(String whatsappMessageId) {
            return new SendResult(true, whatsappMessageId, "whatsapp", "sent");
        }

        static SendResult failed(String detail) {
            return new SendResult(false, null, "whatsapp", detail);
        }
    }

    private final WhatsAppChannelCredentialsService credentialsService;
    private final ObjectMapper objectMapper;

    @Value("${app.integrations.whatsapp.send.http-timeout-seconds:15}")
    private int httpTimeoutSeconds;

    /**
     * Meta Graph base URL. Overridable for tests / on-prem proxies; default is Meta cloud.
     */
    @Value("${app.integrations.whatsapp.send.graph-base-url:https://graph.facebook.com}")
    private String graphBaseUrl;

    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.facebook.com";

    /**
     * Send a plain text message. {@code toDigits} is the recipient MSISDN without {@code +}
     * (e.g. {@code 254712345678}) — matching how {@code crm_contact.phone_e164} is stored.
     */
    public SendResult sendText(String toDigits, String body) {
        return sendText(toDigits, body, null);
    }

    /**
     * Send a plain text message. {@code toDigits} is the recipient MSISDN without {@code +}
     * (e.g. {@code 254712345678}) — matching how {@code crm_contact.phone_e164} is stored.
     *
     * @param fromPhoneNumberId the shop's own Meta number to send from; {@code null}/blank uses
     *                          the platform default number
     */
    public SendResult sendText(String toDigits, String body, String fromPhoneNumberId) {
        if (toDigits == null || toDigits.isBlank()) {
            return SendResult.failed("missing_to");
        }
        if (body == null || body.isBlank()) {
            return SendResult.failed("missing_body");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toDigits.trim());
        payload.put("type", "text");
        payload.put("text", Map.of("body", body));
        return dispatch(fromPhoneNumberId, payload);
    }

    /**
     * Send an approved template. Reaches recipients outside the 24h window (broadcast template
     * mode). {@code params} are positional body values ({@code {{1}}}, {@code {{2}}}, …).
     */
    public SendResult sendTemplate(String toDigits, String templateName, String language, List<String> params) {
        return sendTemplate(toDigits, templateName, language, params, null);
    }

    public SendResult sendTemplate(
            String toDigits, String templateName, String language, List<String> params, String fromPhoneNumberId) {
        if (toDigits == null || toDigits.isBlank()) {
            return SendResult.failed("missing_to");
        }
        if (templateName == null || templateName.isBlank()) {
            return SendResult.failed("missing_template");
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName.trim());
        template.put("language", Map.of("code", language == null || language.isBlank() ? "en_US" : language.trim()));
        if (params != null && !params.isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String param : params) {
                parameters.add(Map.of("type", "text", "text", param == null ? "" : param));
            }
            template.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toDigits.trim());
        payload.put("type", "template");
        payload.put("template", template);
        return dispatch(fromPhoneNumberId, payload);
    }

    private SendResult dispatch(String fromPhoneNumberId, Map<String, Object> payload) {
        WhatsAppChannelCredentialsService.OutboundCredentials credentials =
                credentialsService.outboundCredentials(fromPhoneNumberId);
        if (credentials == null) {
            return SendResult.failed("whatsapp_credentials_not_configured");
        }

        String version = credentials.graphVersion() == null || credentials.graphVersion().isBlank()
                ? "v25.0"
                : credentials.graphVersion().trim();
        String base = graphBaseUrl == null || graphBaseUrl.isBlank()
                ? DEFAULT_GRAPH_BASE_URL
                : graphBaseUrl.trim();
        String url = base + "/" + version + "/" + credentials.phoneNumberId().trim() + "/messages";

        try {
            HttpResponse<String> response = Unirest.post(url)
                    .connectTimeout(httpTimeoutSeconds * 1000)
                    .socketTimeout(httpTimeoutSeconds * 1000)
                    .header("Authorization", "Bearer " + credentials.accessToken().trim())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(payload))
                    .asString();

            int code = response.getStatus();
            if (code >= 200 && code < 300) {
                return SendResult.ok(extractWamid(response.getBody()));
            }
            log.warn("WhatsApp channel send HTTP {} body={}", code, truncate(response.getBody()));
            return SendResult.failed("http_" + code + ":" + truncate(response.getBody()));
        } catch (Exception ex) {
            log.warn("WhatsApp channel send failed: {}", ex.getMessage());
            return SendResult.failed(truncate(ex.getMessage()));
        }
    }

    /** Meta's success body: {@code messages: [{ id: "wamid...." }]}. */
    private String extractWamid(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode messages = objectMapper.readTree(responseBody).path("messages");
            if (messages.isArray() && !messages.isEmpty()) {
                JsonNode id = messages.get(0).path("id");
                return id.isMissingNode() ? null : id.asText(null);
            }
        } catch (Exception ignored) {
            // Best effort — the send already succeeded.
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
