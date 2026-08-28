package zelisline.ub.messaging.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.messaging.application.TenantMessagingConfig;

/**
 * Sends WhatsApp messages via Meta Graph API ({@code /messages}).
 */
@Component
public class MetaWhatsAppMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppMessagingClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param toDigits MSISDN without + (e.g. 254712345678)
     */
    public SendResult sendText(TenantMessagingConfig cfg, String toDigits, String body) {
        if (body == null || body.isBlank()) {
            return SendResult.failed("missing_body");
        }
        return send(cfg, toDigits, Map.of(
                "type", "text",
                "text", Map.of("body", body)));
    }

    /**
     * Sends a pre-approved template message (required for cold outreach outside the
     * 24-hour customer service window).
     */
    public SendResult sendTemplate(TenantMessagingConfig cfg, String toDigits, String templateName, String languageCode) {
        return sendTemplate(cfg, toDigits, templateName, languageCode, List.of());
    }

    /**
     * Sends a pre-approved template with body variables ({{1}}, {{2}}, …).
     *
     * <p>Retries common language codes and component shapes. Cold sends often fail while
     * free-form chat to recently-messaged numbers still works because Meta registers English
     * utility templates as {@code en_US}, or expects the pay-link as a URL button parameter
     * rather than a body variable.
     */
    public SendResult sendTemplate(
            TenantMessagingConfig cfg,
            String toDigits,
            String templateName,
            String languageCode,
            List<String> bodyParams
    ) {
        if (templateName == null || templateName.isBlank()) {
            return SendResult.failed("missing_template_name");
        }
        String name = templateName.trim();
        List<String> params = sanitizeParams(bodyParams);
        SendResult last = null;

        for (String lang : languageCandidates(languageCode)) {
            for (Map<String, Object> template : templatePayloadCandidates(name, lang, params)) {
                last = send(cfg, toDigits, Map.of("type", "template", "template", template));
                if (last.sent()) {
                    log.info("Meta WhatsApp template '{}' accepted language={} shape={}",
                            name, lang, shapeLabel(template));
                    return last;
                }
                if (last.authFailure() || last.templatePaused()) {
                    return last;
                }
                if (!looksLikeRetryableTemplateError(last.detail())) {
                    return last;
                }
                log.warn("Meta WhatsApp template '{}' rejected language={} shape={} ({})",
                        name, lang, shapeLabel(template), last.detail());
            }
        }
        return last != null ? last : SendResult.failed("template_send_failed");
    }

    private SendResult send(TenantMessagingConfig cfg, String toDigits, Object payload) {
        if (!cfg.metaWhatsAppConfigured()) {
            return SendResult.skipped("Meta WhatsApp not configured");
        }
        if (toDigits == null || toDigits.isBlank()) {
            return SendResult.failed("missing_to");
        }
        String token = cfg.metaAccessToken() == null ? "" : cfg.metaAccessToken().trim();
        if (token.isBlank()) {
            return SendResult.failed("missing_access_token");
        }
        String version = cfg.metaGraphVersion() == null || cfg.metaGraphVersion().isBlank()
                ? "v25.0"
                : cfg.metaGraphVersion().trim();
        String phoneNumberId = cfg.metaPhoneNumberId() == null ? "" : cfg.metaPhoneNumberId().trim();
        String url = "https://graph.facebook.com/" + version + "/" + phoneNumberId + "/messages";
        try {
            var requestBody = new LinkedHashMap<String, Object>();
            requestBody.put("messaging_product", "whatsapp");
            requestBody.put("to", toDigits.trim());
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = (Map<String, Object>) payload;
            requestBody.putAll(payloadMap);
            String json = objectMapper.writeValueAsString(requestBody);
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return SendResult.sent("meta_whatsapp");
            }
            String detail = formatHttpFailure(response.getStatus(), response.getBody());
            log.warn("Meta WhatsApp send HTTP {} body={}", response.getStatus(), truncate(response.getBody()));
            return SendResult.failed(detail, response.getStatus());
        } catch (Exception ex) {
            log.warn("Meta WhatsApp send failed: {}", ex.getMessage());
            return SendResult.failed("error");
        }
    }

    static List<Map<String, Object>> templatePayloadCandidates(
            String templateName,
            String lang,
            List<String> params
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 1) All vars as body parameters (current Palmart preview shape).
        out.add(templatePayload(templateName, lang, bodyComponents(params), null));

        if (params.size() >= 2) {
            // 2) Last var is a dynamic URL button suffix/path; earlier vars are body.
            List<String> bodyOnly = params.subList(0, params.size() - 1);
            String urlParam = urlButtonParameter(params.get(params.size() - 1));
            out.add(templatePayload(templateName, lang, bodyComponents(bodyOnly), urlButtonComponent(urlParam)));
        }

        if (params.size() >= 3) {
            // 3) Common utility shape: name + balance in body, pay link on URL button.
            List<String> twoBody = params.subList(0, 2);
            String urlParam = urlButtonParameter(params.get(params.size() - 1));
            out.add(templatePayload(templateName, lang, bodyComponents(twoBody), urlButtonComponent(urlParam)));
        }

        // 4) No components — only works for templates with zero variables.
        if (!params.isEmpty()) {
            out.add(templatePayload(templateName, lang, List.of(), null));
        }
        return out;
    }

    private static Map<String, Object> templatePayload(
            String templateName,
            String lang,
            List<Map<String, Object>> components,
            Map<String, Object> extraComponent
    ) {
        var template = new LinkedHashMap<String, Object>();
        template.put("name", templateName);
        template.put("language", Map.of("code", lang));
        List<Map<String, Object>> all = new ArrayList<>(components);
        if (extraComponent != null) {
            all.add(extraComponent);
        }
        if (!all.isEmpty()) {
            template.put("components", all);
        }
        return template;
    }

    private static List<Map<String, Object>> bodyComponents(List<String> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        var parameters = new ArrayList<Map<String, Object>>();
        for (String text : params) {
            parameters.add(Map.of("type", "text", "text", text));
        }
        return List.of(Map.of("type", "body", "parameters", parameters));
    }

    private static Map<String, Object> urlButtonComponent(String urlParam) {
        var button = new LinkedHashMap<String, Object>();
        button.put("type", "button");
        button.put("sub_type", "url");
        button.put("index", "0");
        button.put("parameters", List.of(Map.of("type", "text", "text", urlParam)));
        return button;
    }

    /**
     * Meta URL buttons usually take only the dynamic suffix after a fixed base URL.
     * If we have a full URL, pass the path+query; otherwise pass the value as-is.
     */
    static String urlButtonParameter(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String value = raw.trim();
        try {
            if (value.startsWith("http://") || value.startsWith("https://")) {
                java.net.URI uri = java.net.URI.create(value);
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                String query = uri.getRawQuery();
                String suffix = path.startsWith("/") ? path.substring(1) : path;
                if (query != null && !query.isBlank()) {
                    suffix = suffix + "?" + query;
                }
                return suffix.isBlank() ? value : suffix;
            }
        } catch (Exception ignored) {
            // keep raw
        }
        return value;
    }

    static List<String> sanitizeParams(List<String> bodyParams) {
        if (bodyParams == null || bodyParams.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(bodyParams.size());
        for (String param : bodyParams) {
            String text = param == null ? "" : param.trim();
            text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
            if (text.isEmpty()) {
                text = "-";
            }
            if (text.length() > 1024) {
                text = text.substring(0, 1024);
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    static List<String> languageCandidates(String preferred) {
        Set<String> out = new LinkedHashSet<>();
        out.add(normalizeLang(preferred));
        // Meta Business Manager almost always registers English utility templates as en_US.
        out.add("en_US");
        out.add("en");
        out.add("en_GB");
        return List.copyOf(out);
    }

    private static String normalizeLang(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return "en_US";
        }
        return languageCode.trim();
    }

    static boolean looksLikeRetryableTemplateError(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        // Paused / pacing / quality holds are not fixed by language/shape retries.
        if (looksLikeTemplatePaused(detail)) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("template")
                || d.contains("language")
                || d.contains("locale")
                || d.contains("parameter")
                || d.contains("component")
                || d.contains("translation")
                || d.contains("not exist")
                || d.contains("does not exist")
                || d.contains("132000")
                || d.contains("132001")
                || d.contains("132005")
                || d.contains("132007")
                || d.contains("132012")
                || d.contains("131008")
                || d.contains("131009");
    }

    /**
     * Graph permission / app-access block (code 200). Token may decode fine, but Meta
     * will not let this app or system user call WhatsApp Cloud API.
     */
    public static boolean looksLikePermissionBlocked(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("api access blocked")
                || d.contains("permissions error")
                || d.contains("[code=200]")
                || d.contains("[code=200 ")
                || d.contains("permission is either not granted");
    }

    /** Meta paused the template (low quality) or paced it — typically 3h / 6h. */
    static boolean looksLikeTemplatePaused(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("paused")
                || d.contains("temporarily unavailable")
                || d.contains("template pacing")
                || d.contains("message template is paused")
                || d.contains("133016");
    }

    private static String shapeLabel(Map<String, Object> template) {
        Object components = template.get("components");
        if (!(components instanceof List<?> list) || list.isEmpty()) {
            return "no_components";
        }
        boolean hasButton = false;
        int bodyParams = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object type = map.get("type");
            if ("button".equals(type)) {
                hasButton = true;
            }
            if ("body".equals(type) && map.get("parameters") instanceof List<?> params) {
                bodyParams = params.size();
            }
        }
        return hasButton ? "body" + bodyParams + "+url_button" : "body" + bodyParams;
    }

    static String formatHttpFailure(int status, String rawBody) {
        String metaMessage = parseMetaErrorMessage(rawBody);
        if (metaMessage == null || metaMessage.isBlank()) {
            return "http_" + status;
        }
        return "http_" + status + ": " + metaMessage;
    }

    static String parseMetaErrorMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(rawBody);
            JsonNode error = root.get("error");
            if (error == null || error.isNull()) {
                return null;
            }
            String message = textField(error, "message");
            String type = textField(error, "type");
            String code = error.has("code") && !error.get("code").isNull()
                    ? error.get("code").asText()
                    : null;
            String subcode = error.has("error_subcode") && !error.get("error_subcode").isNull()
                    ? error.get("error_subcode").asText()
                    : null;
            StringBuilder sb = new StringBuilder();
            if (type != null && !type.isBlank()) {
                sb.append(type).append(" — ");
            }
            if (message != null) {
                sb.append(message);
            }
            if (code != null || subcode != null) {
                sb.append(" [code=").append(code != null ? code : "?");
                if (subcode != null) {
                    sb.append(" subcode=").append(subcode);
                }
                sb.append(']');
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String textField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    public record SendResult(boolean sent, boolean skipped, String channel, String detail, Integer httpStatus) {
        public static SendResult sent(String channel) {
            return new SendResult(true, false, channel, "sent", null);
        }

        public static SendResult skipped(String detail) {
            return new SendResult(false, true, "whatsapp", detail, null);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, "whatsapp", detail, null);
        }

        public static SendResult failed(String detail, int httpStatus) {
            return new SendResult(false, false, "whatsapp", detail, httpStatus);
        }

        public boolean authFailure() {
            if (httpStatus != null && (httpStatus == 401 || httpStatus == 403)) {
                return true;
            }
            return permissionBlocked();
        }

        public boolean permissionBlocked() {
            return looksLikePermissionBlocked(detail);
        }

        public boolean templatePaused() {
            return looksLikeTemplatePaused(detail);
        }
    }
}
