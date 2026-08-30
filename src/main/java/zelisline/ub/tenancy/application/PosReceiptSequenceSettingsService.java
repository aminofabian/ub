package zelisline.ub.tenancy.application;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * POS receipt sequence floor stored in {@code businesses.settings.nextReceiptNo}.
 * Allocation uses {@code max(MAX(receipt_no)+1, floor)} so shops can jump ahead
 * when migrating from another till without rewriting history.
 */
@Service
@RequiredArgsConstructor
public class PosReceiptSequenceSettingsService {

    private static final String KEY_NEXT_RECEIPT_NO = "nextReceiptNo";

    private final ObjectMapper objectMapper;

    public Long readNextReceiptNo(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            if (!root.isObject()) {
                return null;
            }
            JsonNode node = root.get(KEY_NEXT_RECEIPT_NO);
            if (node == null || node.isNull() || !node.isNumber()) {
                return null;
            }
            long value = node.asLong();
            return value >= 1L ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    public String merge(String currentSettings, Long nextReceiptNo) {
        if (nextReceiptNo == null) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        if (nextReceiptNo < 1L) {
            root.remove(KEY_NEXT_RECEIPT_NO);
        } else {
            root.put(KEY_NEXT_RECEIPT_NO, nextReceiptNo);
        }
        return writeRoot(root);
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

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not save receipt sequence settings", e);
        }
    }
}
