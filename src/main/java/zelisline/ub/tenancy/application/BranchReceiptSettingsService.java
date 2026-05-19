package zelisline.ub.tenancy.application;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsPatch;
import zelisline.ub.tenancy.api.dto.BranchReceiptSettingsResponse;

@Service
@RequiredArgsConstructor
public class BranchReceiptSettingsService {

    private static final String KEY_PHONE = "phone";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_WEBSITE = "website";
    private static final String KEY_FOOTER_NOTE = "footerNote";

    private final ObjectMapper objectMapper;

    public BranchReceiptSettingsResponse read(String receiptSettingsJson) {
        if (receiptSettingsJson == null || receiptSettingsJson.isBlank()) {
            return BranchReceiptSettingsResponse.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(receiptSettingsJson);
            if (!root.isObject()) {
                return BranchReceiptSettingsResponse.empty();
            }
            return new BranchReceiptSettingsResponse(
                    textOrNull(root.get(KEY_PHONE)),
                    textOrNull(root.get(KEY_EMAIL)),
                    textOrNull(root.get(KEY_WEBSITE)),
                    textOrNull(root.get(KEY_FOOTER_NOTE))
            );
        } catch (Exception e) {
            return BranchReceiptSettingsResponse.empty();
        }
    }

    public String merge(String currentJson, BranchReceiptSettingsPatch patch) {
        if (patch == null) {
            return currentJson;
        }
        ObjectNode root = parseRoot(currentJson);
        if (patch.phone() != null) {
            putOrRemove(root, KEY_PHONE, patch.phone());
        }
        if (patch.email() != null) {
            putOrRemove(root, KEY_EMAIL, patch.email());
        }
        if (patch.website() != null) {
            putOrRemove(root, KEY_WEBSITE, patch.website());
        }
        if (patch.footerNote() != null) {
            putOrRemove(root, KEY_FOOTER_NOTE, patch.footerNote());
        }
        if (root.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize branch receipt settings", e);
        }
    }

    private ObjectNode parseRoot(String currentJson) {
        if (currentJson == null || currentJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(currentJson);
            if (node.isObject()) {
                return (ObjectNode) node.deepCopy();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return objectMapper.createObjectNode();
    }

    private static void putOrRemove(ObjectNode root, String key, String raw) {
        if (raw == null || raw.isBlank()) {
            root.remove(key);
        } else {
            root.put(key, raw.trim());
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String t = node.asText().trim();
        return t.isEmpty() ? null : t;
    }
}
