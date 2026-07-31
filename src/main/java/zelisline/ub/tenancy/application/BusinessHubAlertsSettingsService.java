package zelisline.ub.tenancy.application;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.HubAlertsPatchRequest;
import zelisline.ub.tenancy.api.dto.HubAlertsSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessHubAlertsSettingsService {

    private static final String KEY_HUB_ALERTS = "hubAlerts";
    private static final String KEY_VOLUME = "volume";

    private final ObjectMapper objectMapper;

    public HubAlertsSettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return HubAlertsSettingsResponse.defaults();
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            if (!root.isObject()) {
                return HubAlertsSettingsResponse.defaults();
            }
            JsonNode hub = root.path(KEY_HUB_ALERTS);
            if (!hub.isObject()) {
                return HubAlertsSettingsResponse.defaults();
            }
            return new HubAlertsSettingsResponse(readVolume(hub.path(KEY_VOLUME)));
        } catch (Exception e) {
            return HubAlertsSettingsResponse.defaults();
        }
    }

    public String merge(String currentSettings, HubAlertsPatchRequest patch) {
        if (patch == null || patch.volume() == null) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode hub = copyNamespace(root, KEY_HUB_ALERTS);
        hub.put(KEY_VOLUME, clampVolume(patch.volume()));
        root.set(KEY_HUB_ALERTS, hub);
        return writeRoot(root);
    }

    private static int readVolume(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return HubAlertsSettingsResponse.DEFAULT_VOLUME;
        }
        return clampVolume(node.asInt());
    }

    private static int clampVolume(int raw) {
        return Math.max(1, Math.min(100, raw));
    }

    private ObjectNode parseRoot(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(settingsJson);
            if (parsed != null && parsed.isObject()) {
                return (ObjectNode) parsed;
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return objectMapper.createObjectNode();
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write hubAlerts settings", e);
        }
    }
}
