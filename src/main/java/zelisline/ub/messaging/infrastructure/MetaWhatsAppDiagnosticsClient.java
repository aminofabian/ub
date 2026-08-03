package zelisline.ub.messaging.infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.messaging.application.TenantMessagingConfig;

/**
 * Read-only Meta Graph queries used to explain why business-initiated (cold) WhatsApp
 * sends fail while replies inside the 24h window still work.
 *
 * <p>Cold delivery depends on template state and account health, which live in Meta —
 * not in our code — so this surfaces Meta's own answer.
 */
@Component
public class MetaWhatsAppDiagnosticsClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppDiagnosticsClient.class);

    private static final String PHONE_FIELDS_FULL =
            "id,display_phone_number,verified_name,quality_rating,name_status,"
                    + "code_verification_status,messaging_limit_tier,platform_type";
    private static final String PHONE_FIELDS_MINIMAL = "id,display_phone_number,verified_name,quality_rating";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PhoneHealth fetchPhoneHealth(TenantMessagingConfig cfg) {
        Result full = get(cfg, cfg.metaPhoneNumberId(), "fields=" + PHONE_FIELDS_FULL);
        Result used = full.ok() ? full : get(cfg, cfg.metaPhoneNumberId(), "fields=" + PHONE_FIELDS_MINIMAL);
        if (!used.ok()) {
            return new PhoneHealth(null, null, null, null, null, used.error());
        }
        JsonNode node = used.body();
        return new PhoneHealth(
                text(node, "display_phone_number"),
                text(node, "verified_name"),
                text(node, "quality_rating"),
                text(node, "messaging_limit_tier"),
                text(node, "name_status"),
                null);
    }

    /**
     * Attempts to discover the WhatsApp Business Account that owns this phone number.
     * Some tokens cannot traverse this edge, so callers must tolerate a null id.
     */
    public WabaLookup fetchWabaId(TenantMessagingConfig cfg) {
        Result result = get(cfg, cfg.metaPhoneNumberId(), "fields=whatsapp_business_account{id,name}");
        if (!result.ok()) {
            return new WabaLookup(null, null, result.error());
        }
        JsonNode waba = result.body().get("whatsapp_business_account");
        if (waba == null || waba.isNull()) {
            return new WabaLookup(null, null, "whatsapp_business_account not returned for this token");
        }
        return new WabaLookup(text(waba, "id"), text(waba, "name"), null);
    }

    public TemplateLookup fetchTemplates(TenantMessagingConfig cfg, String wabaId) {
        if (wabaId == null || wabaId.isBlank()) {
            return new TemplateLookup(List.of(), "No WhatsApp Business Account ID available");
        }
        Result result = get(cfg, wabaId.trim(), "limit=200&fields=name,language,status,category,rejected_reason,quality_score", "message_templates");
        if (!result.ok()) {
            return new TemplateLookup(List.of(), result.error());
        }
        JsonNode data = result.body().get("data");
        if (data == null || !data.isArray()) {
            return new TemplateLookup(List.of(), "Unexpected template response shape");
        }
        List<TemplateInfo> templates = new ArrayList<>();
        for (JsonNode item : data) {
            templates.add(new TemplateInfo(
                    text(item, "name"),
                    text(item, "language"),
                    upper(text(item, "status")),
                    upper(text(item, "category")),
                    text(item, "rejected_reason"),
                    qualityScore(item)));
        }
        return new TemplateLookup(List.copyOf(templates), null);
    }

    private Result get(TenantMessagingConfig cfg, String nodeId, String query) {
        return get(cfg, nodeId, query, null);
    }

    private Result get(TenantMessagingConfig cfg, String nodeId, String query, String edge) {
        if (!cfg.metaWhatsAppConfigured()) {
            return Result.error("Meta WhatsApp is not configured");
        }
        if (nodeId == null || nodeId.isBlank()) {
            return Result.error("missing_node_id");
        }
        String version = cfg.metaGraphVersion() == null || cfg.metaGraphVersion().isBlank()
                ? "v25.0"
                : cfg.metaGraphVersion().trim();
        String url = "https://graph.facebook.com/" + version + "/"
                + URLEncoder.encode(nodeId.trim(), StandardCharsets.UTF_8)
                + (edge == null ? "" : "/" + edge)
                + "?" + query;
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + cfg.metaAccessToken().trim())
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                String detail = MetaWhatsAppMessagingClient.formatHttpFailure(
                        response.getStatus(), response.getBody());
                return Result.error(detail);
            }
            return Result.ok(objectMapper.readTree(response.getBody()));
        } catch (Exception ex) {
            log.warn("Meta diagnostics call failed url={} error={}", url, ex.getMessage());
            return Result.error("request_failed: " + ex.getMessage());
        }
    }

    private static String qualityScore(JsonNode item) {
        JsonNode score = item.get("quality_score");
        if (score == null || score.isNull()) {
            return null;
        }
        if (score.isObject()) {
            return text(score, "score");
        }
        String value = score.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private record Result(JsonNode body, String error) {
        static Result ok(JsonNode body) {
            return new Result(body, null);
        }

        static Result error(String error) {
            return new Result(null, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    public record PhoneHealth(
            String displayPhoneNumber,
            String verifiedName,
            String qualityRating,
            String messagingLimitTier,
            String nameStatus,
            String error
    ) {
    }

    public record WabaLookup(String id, String name, String error) {
    }

    public record TemplateLookup(List<TemplateInfo> templates, String error) {
    }

    public record TemplateInfo(
            String name,
            String language,
            String status,
            String category,
            String rejectedReason,
            String qualityScore
    ) {
    }
}
