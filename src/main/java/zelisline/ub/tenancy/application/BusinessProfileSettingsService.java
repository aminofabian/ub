package zelisline.ub.tenancy.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.ProfilePatchRequest;
import zelisline.ub.tenancy.api.dto.ProfileSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessProfileSettingsService {

    static final String KEY_PROFILE = "profile";
    private static final String KEY_STORE_TYPE = "storeType";
    private static final String KEY_STORE_TYPES = "storeTypes";

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
            List<String> storeTypes = readStoreTypes(profile);
            String storeType = storeTypes.isEmpty() ? null : storeTypes.get(0);
            return new ProfileSettingsResponse(storeType, storeTypes.isEmpty() ? null : storeTypes);
        } catch (Exception e) {
            return ProfileSettingsResponse.empty();
        }
    }

    public String merge(String currentSettings, ProfilePatchRequest patch) {
        if (patch == null) {
            return currentSettings;
        }
        if (patch.storeTypes() != null) {
            return mergeStoreTypes(currentSettings, patch.storeTypes());
        }
        if (patch.storeType() != null) {
            return mergeStoreTypes(currentSettings, List.of(patch.storeType().trim()));
        }
        return currentSettings;
    }

    public String mergeStoreType(String currentSettings, String storeType) {
        if (storeType == null || storeType.isBlank()) {
            return currentSettings;
        }
        return mergeStoreTypes(currentSettings, List.of(storeType.trim()));
    }

    public String mergeStoreTypes(String currentSettings, List<String> storeTypes) {
        if (storeTypes == null || storeTypes.isEmpty()) {
            return currentSettings;
        }
        List<String> normalized = normalizeStoreTypes(storeTypes);
        if (normalized.isEmpty()) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode profile = copyNamespace(root, KEY_PROFILE);
        profile.put(KEY_STORE_TYPE, normalized.get(0));
        profile.set(KEY_STORE_TYPES, toArray(normalized));
        root.set(KEY_PROFILE, profile);
        return writeRoot(root);
    }

    private static List<String> readStoreTypes(JsonNode profile) {
        List<String> storeTypes = readStringList(profile.path(KEY_STORE_TYPES));
        if (!storeTypes.isEmpty()) {
            return storeTypes;
        }
        String legacy = textOrNull(profile.path(KEY_STORE_TYPE));
        if (legacy == null) {
            return List.of();
        }
        return List.of(legacy);
    }

    private static List<String> readStringList(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item.isTextual()) {
                String value = item.asText().trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static List<String> normalizeStoreTypes(List<String> storeTypes) {
        List<String> out = new ArrayList<>();
        for (String storeType : storeTypes) {
            if (storeType == null) {
                continue;
            }
            String trimmed = storeType.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private ArrayNode toArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value.trim());
            }
        }
        return array;
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
