package zelisline.ub.integrations.whatsapp.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Turns a shop's plain WhatsApp number into the Meta {@code phone_number_id} that drives routing.
 *
 * <p>With the shared platform WABA the shop only types its number; the platform lists its own
 * numbers via {@code GET /{waba-id}/phone_numbers} and matches on {@code display_phone_number}, so
 * the tenant never sees a Meta id. See {@code docs/scopes/whatsapp-crm/KEYS.md}.
 */
@Component
public class MetaWhatsAppNumberResolver {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppNumberResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.facebook.com";
    private static final String DEFAULT_GRAPH_VERSION = "v25.0";

    /** Default country for bare/local numbers; the platform is Kenya-first (M-Pesa, KES). */
    private static final String DEFAULT_COUNTRY_CODE = "254";

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Value("${app.integrations.whatsapp.send.graph-base-url:https://graph.facebook.com}")
    private String graphBaseUrl;

    public MetaWhatsAppNumberResolver(PlatformIntegrationSettingsService platformIntegrationSettingsService) {
        this.platformIntegrationSettingsService = platformIntegrationSettingsService;
    }

    public record Resolved(String phoneNumberId, String displayNumber, String wabaId) {
    }

    /**
     * @param input what the shop typed — {@code +254 712…}, {@code 0712…}, {@code 254712…}, or a raw
     *              Meta phone_number_id they saved before
     * @throws ResponseStatusException 400 when it cannot be resolved to a platform-WABA number
     */
    public Resolved resolve(String input) {
        String digits = normalize(input);
        if (digits == null) {
            throw badRequest("Enter a WhatsApp number.");
        }
        ResolvedMetaWhatsAppConfig config = platformIntegrationSettingsService.resolveMetaWhatsApp();
        String accessToken = trimToNull(config.accessToken());
        String platformPhoneNumberId = trimToNull(config.phoneNumberId());
        if (accessToken == null || platformPhoneNumberId == null) {
            throw badRequest("Platform WhatsApp isn't set up yet — contact support.");
        }
        String version = firstNonBlank(config.graphVersion(), DEFAULT_GRAPH_VERSION);
        String base = firstNonBlank(graphBaseUrl, DEFAULT_GRAPH_BASE_URL);

        String wabaId = fetchWabaId(base, version, platformPhoneNumberId, accessToken);
        if (wabaId == null) {
            throw badRequest("Could not read the Palmart WhatsApp account — contact support.");
        }
        for (Resolved candidate : listNumbers(base, version, wabaId, accessToken)) {
            String candidateDigits = normalize(candidate.displayNumber());
            if (digits.equals(candidateDigits)) {
                return new Resolved(candidate.phoneNumberId(), candidateDigits, wabaId);
            }
        }
        throw badRequest(
                "We couldn't find " + digits + " on the Palmart WhatsApp account. Add this number in "
                        + "Meta Business Settings (WhatsApp Accounts → Phone numbers), then try again — "
                        + "or contact support.");
    }

    private String fetchWabaId(String base, String version, String phoneNumberId, String accessToken) {
        String url = base + "/" + version + "/" + encode(phoneNumberId)
                + "?fields=whatsapp_business_account{id}";
        JsonNode node = get(url, accessToken);
        return node == null ? null : text(node.path("whatsapp_business_account"), "id");
    }

    private List<Resolved> listNumbers(String base, String version, String wabaId, String accessToken) {
        String url = base + "/" + version + "/" + encode(wabaId)
                + "/phone_numbers?fields=id,display_phone_number&limit=200";
        JsonNode root = get(url, accessToken);
        List<Resolved> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode node : root.path("data")) {
                String id = text(node, "id");
                if (id != null) {
                    out.add(new Resolved(id, text(node, "display_phone_number")));
                }
            }
        }
        return out;
    }

    private static JsonNode get(String url, String accessToken) {
        try {
            HttpResponse<String> response = Unirest.get(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("Meta number lookup failed (HTTP {}): {}", response.getStatus(), response.getBody());
                return null;
            }
            return MAPPER.readTree(response.getBody());
        } catch (Exception ex) {
            log.warn("Meta number lookup failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Digits-only, Kenya-normalized: {@code +254 712 345 678} / {@code 0712345678} / {@code 712345678}
     * all become {@code 254712345678}. A value that already carries another country code is left as-is.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return DEFAULT_COUNTRY_CODE + digits.substring(1);
        }
        if (digits.length() == 9) {
            return DEFAULT_COUNTRY_CODE + digits;
        }
        return digits;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
