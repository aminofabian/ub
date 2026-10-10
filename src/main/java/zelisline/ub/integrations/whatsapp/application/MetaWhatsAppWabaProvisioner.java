package zelisline.ub.integrations.whatsapp.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Automates the Meta-side steps for the shared platform WABA, so an operator (or tenant) never has to
 * click through Business Manager for each number.
 *
 * <ol>
 *   <li><b>Subscribe</b> — {@code POST /{waba-id}/subscribed_apps} makes the platform app receive
 *       webhooks for every number on the WABA. Idempotent.</li>
 *   <li><b>Register</b> — {@code POST /{phone_number_id}/register} enables a number for the Cloud
 *       API; only attempted when {@code WHATSAPP_META_REGISTRATION_PIN} is configured, since Meta
 *       requires a 6-digit PIN. "Already registered" is treated as success.</li>
 * </ol>
 */
@Component
public class MetaWhatsAppWabaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWabaProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.facebook.com";
    private static final String DEFAULT_GRAPH_VERSION = "v25.0";

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Value("${app.integrations.whatsapp.send.graph-base-url:https://graph.facebook.com}")
    private String graphBaseUrl;

    @Value("${app.messaging.meta-whatsapp.registration-pin:${WHATSAPP_META_REGISTRATION_PIN:}}")
    private String registrationPin;

    public MetaWhatsAppWabaProvisioner(PlatformIntegrationSettingsService platformIntegrationSettingsService) {
        this.platformIntegrationSettingsService = platformIntegrationSettingsService;
    }

    public record Outcome(boolean ok, String detail) {
        static Outcome ok(String detail) {
            return new Outcome(true, detail);
        }

        static Outcome failed(String detail) {
            return new Outcome(false, detail);
        }
    }

    /** Idempotently subscribe the platform app to the WABA (safe to call on every add). */
    public Outcome ensureSubscribed(String wabaId) {
        Config config = config();
        if (config == null) {
            return Outcome.failed("Platform WhatsApp isn't configured");
        }
        if (isBlank(wabaId)) {
            return Outcome.failed("No WhatsApp Business Account to subscribe");
        }
        String url = config.base() + "/" + config.version() + "/" + encode(wabaId) + "/subscribed_apps";
        try {
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + config.token())
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return Outcome.ok("Subscribed the platform app to " + wabaId);
            }
            return Outcome.failed(metaError(response));
        } catch (Exception ex) {
            log.warn("Meta subscribe call failed: {}", ex.getMessage());
            return Outcome.failed("Could not reach Meta: " + ex.getMessage());
        }
    }

    /**
     * Register a number for the Cloud API when a registration PIN is configured. No PIN means the
     * numbers were already registered in Business Manager, so this is skipped.
     */
    public Outcome ensureRegistered(String phoneNumberId) {
        if (isBlank(phoneNumberId)) {
            return Outcome.failed("No phone number id to register");
        }
        if (isBlank(registrationPin)) {
            return Outcome.ok("Skipped registration (WHATSAPP_META_REGISTRATION_PIN not set)");
        }
        Config config = config();
        if (config == null) {
            return Outcome.failed("Platform WhatsApp isn't configured");
        }
        String url = config.base() + "/" + config.version() + "/" + encode(phoneNumberId) + "/register";
        String body = "{\"messaging_product\":\"whatsapp\",\"pin\":\"" + registrationPin.trim() + "\"}";
        try {
            HttpResponse<String> response = Unirest.post(url)
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .asString();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                return Outcome.ok("Registered " + phoneNumberId);
            }
            if (isAlreadyRegistered(response.getBody())) {
                return Outcome.ok(phoneNumberId + " was already registered");
            }
            return Outcome.failed(metaError(response));
        } catch (Exception ex) {
            log.warn("Meta register call failed: {}", ex.getMessage());
            return Outcome.failed("Could not reach Meta: " + ex.getMessage());
        }
    }

    /** True when a 6-digit registration PIN is configured (registration is attempted). */
    public boolean registrationPinConfigured() {
        return !isBlank(registrationPin);
    }

    private Config config() {
        ResolvedMetaWhatsAppConfig meta = platformIntegrationSettingsService.resolveMetaWhatsApp();
        String token = trimToNull(meta.accessToken());
        if (token == null) {
            return null;
        }
        String base = isBlank(graphBaseUrl) ? DEFAULT_GRAPH_BASE_URL : graphBaseUrl.trim();
        String version = isBlank(meta.graphVersion()) ? DEFAULT_GRAPH_VERSION : meta.graphVersion().trim();
        return new Config(base, version, token);
    }

    private record Config(String base, String version, String token) {
    }

    private static boolean isAlreadyRegistered(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("already registered") || lower.contains("\"code\":133005");
    }

    private static String metaError(HttpResponse<String> response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return "Meta call failed (HTTP " + response.getStatus() + ")";
        }
        try {
            String message = text(MAPPER.readTree(body).path("error"), "message");
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
