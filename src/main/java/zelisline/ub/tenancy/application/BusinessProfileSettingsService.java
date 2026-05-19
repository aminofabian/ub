package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.ProfilePatchRequest;
import zelisline.ub.tenancy.api.dto.ProfileSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessProfileSettingsService {

    static final String KEY_PROFILE = "profile";
    private static final String KEY_STORE_TYPE = "storeType";

    private final ObjectMapper objectMapper;

    public ProfileSettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return ProfileSettingsResponse.empty();
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return ProfileSettingsResponse.empty();
            }
            JsonNode profile = root.path(KEY_PROFILE);
            if (!profile.isObject()) {
                return ProfileSettingsResponse.empty();
            }
            String storeType = textOrNull(profile.path(KEY_STORE_TYPE));
            return new ProfileSettingsResponse(storeType);
        } catch (Exception e) {
            return ProfileSettingsResponse.empty();
        }
    }

    public String merge(String currentSettings, ProfilePatchRequest patch) {
        if (patch == null || patch.storeType() == null) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode profile = copyNamespace(root, KEY_PROFILE);
        profile.put(KEY_STORE_TYPE, patch.storeType().trim());
        root.set(KEY_PROFILE, profile);
        return writeRoot(root);
    }

    public String mergeStoreType(String currentSettings, String storeType) {
        if (storeType == null || storeType.isBlank()) {
            return currentSettings;
        }
        return merge(currentSettings, new ProfilePatchRequest(storeType.trim()));
    }

    private static String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
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
                    "Could not save profile settings"
            );
        }
    }
}
