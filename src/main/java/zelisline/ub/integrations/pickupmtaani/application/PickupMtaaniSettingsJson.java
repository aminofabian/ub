package zelisline.ub.integrations.pickupmtaani.application;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Read/merge primitives for the {@code pickupMtaani} namespace of
 * {@code businesses.settings} JSON — same pattern as
 * {@link zelisline.ub.tenancy.application.MetaCapiSettingsService}.
 *
 * <p>Shared by the merchant settings service and the super-admin credential
 * service, which write different fields of the same namespace.
 */
@Component
class PickupMtaaniSettingsJson {

    static final String KEY_NAMESPACE = "pickupMtaani";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_API_KEY_ENC = "apiKeyEnc";
    static final String KEY_BUSINESS_ID = "businessId";
    static final String KEY_BUSINESS_NAME = "businessName";
    static final String KEY_ACCOUNT_MODE = "accountMode";
    static final String KEY_ORIGIN_AGENT_ID = "originAgentId";
    static final String KEY_ORIGIN_AGENT_NAME = "originAgentName";
    static final String KEY_ORIGIN_LOCATION_NAME = "originLocationName";
    static final String KEY_FEE_MODE = "feeMode";
    static final String KEY_MARKUP_KES = "markupKes";
    static final String KEY_MODE_AGENT = "agent";
    static final String KEY_MODE_DOORSTEP = "doorstep";
    static final String KEY_BOOK_ON_DISPATCH = "bookOnDispatch";
    static final String KEY_LAST_VERIFIED_AT = "lastVerifiedAt";
    static final String KEY_STATUS = "status";
    static final String KEY_STATUS_DETAIL = "statusDetail";

    static final String MODE_SINGLE_BUSINESS = "single_business";
    static final String MODE_MULTI_BUSINESS = "multi_business";

    static final String STATUS_DISCONNECTED = "disconnected";
    static final String STATUS_CONNECTED = "connected";
    static final String STATUS_ERROR = "error";

    static final String FEE_MODE_PASS_THROUGH = "pass_through";
    static final String FEE_MODE_ABSORB = "absorb";
    static final String FEE_MODE_MARKUP = "markup";

    private final ObjectMapper objectMapper;

    PickupMtaaniSettingsJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The namespace object, or {@code null} when absent or malformed. */
    ObjectNode namespaceOrNull(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseDocument(settingsJson);
            if (!root.isObject()) {
                return null;
            }
            JsonNode ns = root.path(KEY_NAMESPACE);
            return ns.isObject() ? (ObjectNode) ns : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The whole settings document as a mutable object, tolerant of blanks/garbage. */
    ObjectNode parseRoot(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = parseDocument(settingsJson);
            return root.isObject() ? (ObjectNode) root : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    ObjectNode copyNamespace(ObjectNode root) {
        if (root.has(KEY_NAMESPACE) && root.get(KEY_NAMESPACE).isObject()) {
            return (ObjectNode) root.get(KEY_NAMESPACE).deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    String write(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not save Pickup Mtaani settings");
        }
    }

    private JsonNode parseDocument(String raw) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(raw);
        // Some settings rows are stored as a JSON string inside a JSON string.
        return node.isTextual() ? objectMapper.readTree(node.asText()) : node;
    }

    // ── Node coercions ──────────────────────────────────────────────────────

    static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Boolean boolOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isBoolean()) {
            return null;
        }
        return node.booleanValue();
    }

    static boolean boolOrElse(JsonNode node, boolean fallback) {
        Boolean value = boolOrNull(node);
        return value == null ? fallback : value;
    }

    static Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int intOrElse(JsonNode node, int fallback) {
        Long value = longOrNull(node);
        return value == null ? fallback : value.intValue();
    }

    static void putOrRemove(ObjectNode namespace, String key, String raw) {
        if (raw == null || raw.isBlank()) {
            namespace.remove(key);
        } else {
            namespace.put(key, raw.trim());
        }
    }
}
