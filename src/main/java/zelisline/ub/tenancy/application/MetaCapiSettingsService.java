package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.api.dto.MetaCapiPatchRequest;
import zelisline.ub.tenancy.api.dto.MetaCapiSettingsResponse;
import zelisline.ub.tenancy.api.dto.MetaPixelPublicConfig;

/**
 * Per-tenant Meta Pixel + Conversions API configuration, stored under the
 * {@code metaCapi} key of {@code businesses.settings} JSON — same
 * read/merge/copyNamespace pattern as {@link BusinessProfileSettingsService}.
 *
 * <p>The CAPI access token is write-only: it is encrypted at rest with
 * {@link CredentialEncryptionService#encryptSecret} and never appears in any
 * response. Events are only ever sent when {@code enabled} is true and both a
 * pixel id and a stored token are present.
 */
@Service
@RequiredArgsConstructor
public class MetaCapiSettingsService {

    static final String KEY_META_CAPI = "metaCapi";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PIXEL_ID = "pixelId";
    private static final String KEY_ACCESS_TOKEN_ENC = "accessTokenEnc";
    private static final String KEY_TEST_EVENT_CODE = "testEventCode";
    private static final String KEY_CONSENT_REQUIRED = "consentRequired";

    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService credentialEncryptionService;

    public MetaCapiSettingsResponse readFromSettingsJson(String settingsJson) {
        JsonNode metaCapi = namespace(settingsJson);
        if (metaCapi == null || !metaCapi.isObject()) {
            return MetaCapiSettingsResponse.empty();
        }
        return new MetaCapiSettingsResponse(
                boolOrNull(metaCapi.path(KEY_ENABLED)),
                textOrNull(metaCapi.path(KEY_PIXEL_ID)),
                hasText(metaCapi.path(KEY_ACCESS_TOKEN_ENC)),
                boolOrNull(metaCapi.path(KEY_CONSENT_REQUIRED))
        );
    }

    /**
     * Public, secret-free pixel config for the unauthenticated host-resolve
     * payload. Never returns the access token or test event code.
     */
    public MetaPixelPublicConfig readPublicPixelConfig(String settingsJson) {
        JsonNode metaCapi = namespace(settingsJson);
        if (metaCapi == null || !metaCapi.isObject()) {
            return MetaPixelPublicConfig.disabled();
        }
        String pixelId = textOrNull(metaCapi.path(KEY_PIXEL_ID));
        boolean enabled = Boolean.TRUE.equals(boolOrNull(metaCapi.path(KEY_ENABLED)))
                && pixelId != null;
        return new MetaPixelPublicConfig(enabled, pixelId);
    }

    /**
     * Internal config used by the CAPI delivery pipeline. Includes the encrypted
     * access token — never expose this beyond server-side delivery code.
     */
    public MetaCapiRuntimeConfig readRuntimeConfig(String settingsJson) {
        JsonNode metaCapi = namespace(settingsJson);
        if (metaCapi == null || !metaCapi.isObject()) {
            return MetaCapiRuntimeConfig.disabled();
        }
        return new MetaCapiRuntimeConfig(
                Boolean.TRUE.equals(boolOrNull(metaCapi.path(KEY_ENABLED))),
                textOrNull(metaCapi.path(KEY_PIXEL_ID)),
                textOrNull(metaCapi.path(KEY_ACCESS_TOKEN_ENC)),
                textOrNull(metaCapi.path(KEY_TEST_EVENT_CODE))
        );
    }

    /**
     * Server-side-only view of the tenant's CAPI configuration: ready only when
     * enabled with both a pixel id and a stored (encrypted) access token.
     */
    public record MetaCapiRuntimeConfig(
            boolean enabled,
            String pixelId,
            String accessTokenEnc,
            String testEventCode
    ) {
        public static MetaCapiRuntimeConfig disabled() {
            return new MetaCapiRuntimeConfig(false, null, null, null);
        }

        public boolean ready() {
            return enabled && pixelId != null && hasToken();
        }

        public boolean hasToken() {
            return accessTokenEnc != null && !accessTokenEnc.isBlank();
        }
    }

    /**
     * PATCH-merge the {@code metaCapi} namespace without touching sibling
     * namespaces. {@code null} patch fields leave values unchanged; blank values
     * clear them. A non-blank {@code accessToken} is encrypted at rest.
     */
    public String merge(String currentSettings, MetaCapiPatchRequest patch) {
        if (patch == null || hasNoChanges(patch)) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode metaCapi = copyNamespace(root, KEY_META_CAPI);
        if (patch.enabled() != null) {
            metaCapi.put(KEY_ENABLED, patch.enabled());
        }
        if (patch.pixelId() != null) {
            putOrRemove(metaCapi, KEY_PIXEL_ID, patch.pixelId());
        }
        if (patch.accessToken() != null) {
            if (patch.accessToken().isBlank()) {
                metaCapi.remove(KEY_ACCESS_TOKEN_ENC);
            } else {
                metaCapi.put(
                        KEY_ACCESS_TOKEN_ENC,
                        credentialEncryptionService.encryptSecret(patch.accessToken().trim())
                );
            }
        }
        if (patch.testEventCode() != null) {
            putOrRemove(metaCapi, KEY_TEST_EVENT_CODE, patch.testEventCode());
        }
        if (patch.consentRequired() != null) {
            metaCapi.put(KEY_CONSENT_REQUIRED, patch.consentRequired());
        }
        root.set(KEY_META_CAPI, metaCapi);
        return writeRoot(root);
    }

    private static boolean hasNoChanges(MetaCapiPatchRequest patch) {
        return patch.enabled() == null
                && patch.pixelId() == null
                && patch.accessToken() == null
                && patch.testEventCode() == null
                && patch.consentRequired() == null;
    }

    private static void putOrRemove(ObjectNode namespace, String key, String raw) {
        if (raw.isBlank()) {
            namespace.remove(key);
        } else {
            namespace.put(key, raw.trim());
        }
    }

    private static Boolean boolOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isBoolean()) {
            return null;
        }
        return node.booleanValue();
    }

    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean hasText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }

    private JsonNode namespace(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return null;
            }
            return root.path(KEY_META_CAPI);
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode parseRoot(String currentSettings) {
        if (currentSettings == null || currentSettings.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = parseSettingsDocument(currentSettings);
            return root.isObject() ? (ObjectNode) root : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseSettingsDocument(String raw) throws JsonProcessingException {
        JsonNode n = objectMapper.readTree(raw);
        if (n.isTextual()) {
            return objectMapper.readTree(n.asText());
        }
        return n;
    }

    private ObjectNode copyNamespace(ObjectNode root, String key) {
        if (root.has(key) && root.get(key).isObject()) {
            return (ObjectNode) root.get(key).deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save Meta pixel settings"
            );
        }
    }
}
