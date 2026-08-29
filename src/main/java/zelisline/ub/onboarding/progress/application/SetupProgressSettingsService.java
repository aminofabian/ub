package zelisline.ub.onboarding.progress.application;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SetupProgressSettingsService {

    static final String KEY_SETUP_PROGRESS = "setupProgress";
    private static final String KEY_SNOOZED_UNTIL = "snoozedUntil";
    private static final String KEY_DISMISSED_AT = "dismissedAt";
    private static final String KEY_REWARD_GRANTED_AT = "rewardGrantedAt";
    private static final String KEY_REWARD_SMS_CREDITS = "rewardSmsCredits";

    private final ObjectMapper objectMapper;

    public record SetupProgressPrefs(
            Instant snoozedUntil,
            Instant dismissedAt,
            Instant rewardGrantedAt,
            Integer rewardSmsCredits
    ) {
        public boolean snoozed(Instant now) {
            return snoozedUntil != null && snoozedUntil.isAfter(now);
        }

        public boolean rewardGranted() {
            return rewardGrantedAt != null;
        }
    }

    public SetupProgressPrefs read(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return new SetupProgressPrefs(null, null, null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            if (!root.isObject()) {
                return new SetupProgressPrefs(null, null, null, null);
            }
            JsonNode node = root.path(KEY_SETUP_PROGRESS);
            if (!node.isObject()) {
                return new SetupProgressPrefs(null, null, null, null);
            }
            Integer credits = null;
            JsonNode creditsNode = node.get(KEY_REWARD_SMS_CREDITS);
            if (creditsNode != null && creditsNode.isNumber()) {
                credits = creditsNode.intValue();
            }
            return new SetupProgressPrefs(
                    parseInstant(node.get(KEY_SNOOZED_UNTIL)),
                    parseInstant(node.get(KEY_DISMISSED_AT)),
                    parseInstant(node.get(KEY_REWARD_GRANTED_AT)),
                    credits);
        } catch (Exception ex) {
            return new SetupProgressPrefs(null, null, null, null);
        }
    }

    public String snooze(String currentSettings, Instant until) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode progress = copyNamespace(root, KEY_SETUP_PROGRESS);
        if (until == null) {
            progress.putNull(KEY_SNOOZED_UNTIL);
        } else {
            progress.put(KEY_SNOOZED_UNTIL, until.toString());
        }
        root.set(KEY_SETUP_PROGRESS, progress);
        return writeRoot(root);
    }

    public String dismiss(String currentSettings, Instant at) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode progress = copyNamespace(root, KEY_SETUP_PROGRESS);
        progress.put(KEY_DISMISSED_AT, at.toString());
        root.set(KEY_SETUP_PROGRESS, progress);
        return writeRoot(root);
    }

    public String markRewardGranted(String currentSettings, Instant at, int smsCredits) {
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode progress = copyNamespace(root, KEY_SETUP_PROGRESS);
        progress.put(KEY_REWARD_GRANTED_AT, at.toString());
        progress.put(KEY_REWARD_SMS_CREDITS, smsCredits);
        root.set(KEY_SETUP_PROGRESS, progress);
        return writeRoot(root);
    }

    private ObjectNode parseRoot(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            return root.isObject() ? (ObjectNode) root.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private ObjectNode copyNamespace(ObjectNode root, String key) {
        JsonNode existing = root.get(key);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String raw = node.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
