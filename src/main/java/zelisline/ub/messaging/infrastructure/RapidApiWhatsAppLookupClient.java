package zelisline.ub.messaging.infrastructure;

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
 * Checks whether an MSISDN is registered on WhatsApp via RapidAPI WhatsApp OSINT.
 */
@Component
public class RapidApiWhatsAppLookupClient {

    private static final Logger log = LoggerFactory.getLogger(RapidApiWhatsAppLookupClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param e164Phone digits with leading + (e.g. +254712345678)
     */
    public LookupResult lookup(TenantMessagingConfig cfg, String e164Phone) {
        if (!cfg.rapidApiConfigured()) {
            return LookupResult.lookupSkipped("RapidAPI key not configured");
        }
        String phone = e164Phone == null ? "" : e164Phone.trim();
        if (phone.isBlank()) {
            return LookupResult.notRegistered("empty phone");
        }
        try {
            String phoneField = cfg.rapidApiPhoneField() == null || cfg.rapidApiPhoneField().isBlank()
                    ? "phone"
                    : cfg.rapidApiPhoneField().trim();
            String phoneValue = cfg.rapidApiPhoneDigitsOnly() ? digitsOnly(phone) : phone;
            String body = objectMapper.writeValueAsString(java.util.Map.of(phoneField, phoneValue));
            HttpResponse<String> response = Unirest.post(cfg.rapidApiLookupUrl())
                    .header("x-rapidapi-key", cfg.rapidApiKey())
                    .header("x-rapidapi-host", cfg.rapidApiHost())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .asString();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("WhatsApp lookup HTTP {} for {}", response.getStatus(), maskPhone(phone));
                return LookupResult.lookupSkipped("http_" + response.getStatus());
            }
            return parseExists(response.getBody());
        } catch (Exception ex) {
            log.warn("WhatsApp lookup failed for {}: {}", maskPhone(phone), ex.getMessage());
            return LookupResult.lookupSkipped("error");
        }
    }

    private static String digitsOnly(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("\\D+", "");
    }

    private LookupResult parseExists(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return LookupResult.lookupSkipped("empty_body");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (booleanField(root, "isWAContact")
                    || booleanField(root, "is_wa_contact")
                    || booleanField(root, "registered")
                    || booleanField(root, "exists")
                    || booleanField(root, "on_whatsapp")
                    || booleanField(root, "onWhatsapp")
                    || booleanField(root, "has_whatsapp")
                    || booleanField(root, "hasWhatsapp")
                    || booleanField(root, "number_exists")
                    || booleanField(root, "whatsapp")) {
                return LookupResult.registered();
            }
            if (root.has("data") && root.get("data").isObject()) {
                JsonNode data = root.get("data");
                if (booleanField(data, "isWAContact")
                        || booleanField(data, "registered")
                        || booleanField(data, "exists")) {
                    return LookupResult.registered();
                }
            }
            String status = textField(root, "status");
            if ("ok".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                return LookupResult.registered();
            }
            if ("not_found".equalsIgnoreCase(status) || "404".equals(status)) {
                return LookupResult.notRegistered(status);
            }
            String message = textField(root, "message");
            if (message != null && message.toLowerCase(Locale.ROOT).contains("not found")) {
                return LookupResult.notRegistered("not_found");
            }
            // Response shape we don't recognize: treat as inconclusive (skip the gate)
            // rather than a hard "not on WhatsApp", so delivery still attempts WhatsApp.
            return LookupResult.lookupSkipped("unrecognized_response");
        } catch (Exception ex) {
            log.debug("WhatsApp lookup parse error: {}", ex.getMessage());
            return LookupResult.lookupSkipped("parse_error");
        }
    }

    private static boolean booleanField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return false;
        }
        JsonNode v = node.get(field);
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.intValue() != 0;
        }
        String s = v.asText("").trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static String textField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String s = node.get(field).asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String maskPhone(String phone) {
        if (phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…" + phone.substring(phone.length() - 2);
    }

    public record LookupResult(boolean onWhatsApp, boolean skipped, String detail) {
        public static LookupResult registered() {
            return new LookupResult(true, false, "on_whatsapp");
        }

        public static LookupResult notRegistered(String detail) {
            return new LookupResult(false, false, detail);
        }

        public static LookupResult lookupSkipped(String detail) {
            return new LookupResult(false, true, detail);
        }
    }
}
