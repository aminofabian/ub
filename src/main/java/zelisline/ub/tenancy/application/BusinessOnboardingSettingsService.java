package zelisline.ub.tenancy.application;

import java.time.Instant;
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
import zelisline.ub.tenancy.api.dto.OnboardingAnswersDto;
import zelisline.ub.tenancy.api.dto.OnboardingPatchRequest;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessOnboardingSettingsService {

    static final String KEY_ONBOARDING = "onboarding";
    private static final String KEY_STATUS = "status";
    private static final String KEY_STEP = "step";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_COMPLETED_AT = "completedAt";
    private static final String KEY_DISMISSED_AT = "dismissedAt";
    private static final String KEY_ANSWERS = "answers";

    private static final String KEY_BRANCH_COUNT = "branchCount";
    private static final String KEY_BRANCH_LOCALITIES = "branchLocalities";
    private static final String KEY_STORE_TYPE = "storeType";
    private static final String KEY_STORE_TYPES = "storeTypes";
    private static final String KEY_SELECTED_DEPARTMENTS = "selectedDepartments";
    private static final String KEY_ONLINE_STORE = "onlineStore";
    private static final String KEY_DISPLAY_NAME = "displayName";
    private static final String KEY_PRIMARY_COLOR = "primaryColor";
    private static final String KEY_ACCENT_COLOR = "accentColor";

    private final ObjectMapper objectMapper;

    public OnboardingSettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return OnboardingSettingsResponse.defaults();
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return OnboardingSettingsResponse.defaults();
            }
            return readNamespace(root.path(KEY_ONBOARDING));
        } catch (Exception e) {
            return OnboardingSettingsResponse.defaults();
        }
    }

    public String merge(String currentSettings, OnboardingPatchRequest patch) {
        if (patch == null) {
            return currentSettings;
        }
        boolean hasStatus = patch.status() != null && !patch.status().isBlank();
        boolean hasStep = patch.step() != null;
        boolean hasAnswers = patch.answers() != null;
        if (!hasStatus && !hasStep && !hasAnswers) {
            return currentSettings;
        }

        ObjectNode root = parseRoot(currentSettings);
        ObjectNode onboarding = copyNamespace(root, KEY_ONBOARDING);
        Instant now = Instant.now();

        if (hasStatus) {
            String status = patch.status().trim().toLowerCase();
            onboarding.put(KEY_STATUS, status);
            if ("completed".equals(status)) {
                onboarding.put(KEY_COMPLETED_AT, now.toString());
                onboarding.putNull(KEY_DISMISSED_AT);
            } else if ("dismissed".equals(status)) {
                onboarding.put(KEY_DISMISSED_AT, now.toString());
            }
        }
        if (hasStep) {
            onboarding.put(KEY_STEP, patch.step());
        }
        if (hasAnswers) {
            ObjectNode answers = copyNamespace(onboarding, KEY_ANSWERS);
            applyAnswersPatch(answers, patch.answers());
            onboarding.set(KEY_ANSWERS, answers);
        }
        onboarding.put(KEY_UPDATED_AT, now.toString());
        root.set(KEY_ONBOARDING, onboarding);
        return writeRoot(root);
    }

    public String mergeInitialPending(String currentSettings) {
        OnboardingSettingsResponse current = readFromSettingsJson(currentSettings);
        if (!"idle".equals(current.status())) {
            return currentSettings;
        }
        return merge(
                currentSettings,
                new OnboardingPatchRequest("pending", 1, null)
        );
    }

    private OnboardingSettingsResponse readNamespace(JsonNode onboarding) {
        if (!onboarding.isObject()) {
            return OnboardingSettingsResponse.defaults();
        }
        String status = textOrDefault(onboarding.path(KEY_STATUS), "idle");
        int step = onboarding.path(KEY_STEP).asInt(1);
        Instant updatedAt = instantOrNull(onboarding.path(KEY_UPDATED_AT));
        Instant completedAt = instantOrNull(onboarding.path(KEY_COMPLETED_AT));
        Instant dismissedAt = instantOrNull(onboarding.path(KEY_DISMISSED_AT));
        OnboardingAnswersDto answers = readAnswers(onboarding.path(KEY_ANSWERS));
        return new OnboardingSettingsResponse(
                status,
                step,
                updatedAt,
                completedAt,
                dismissedAt,
                answers
        );
    }

    private OnboardingAnswersDto readAnswers(JsonNode answers) {
        if (!answers.isObject()) {
            return null;
        }
        List<String> localities = readStringList(answers.path(KEY_BRANCH_LOCALITIES));
        List<String> departments = readStringList(answers.path(KEY_SELECTED_DEPARTMENTS));
        List<String> storeTypes = readStoreTypes(answers);
        if (
                answers.path(KEY_BRANCH_COUNT).isMissingNode()
                        && localities.isEmpty()
                        && storeTypes.isEmpty()
                        && departments.isEmpty()
                        && answers.path(KEY_ONLINE_STORE).isMissingNode()
                        && answers.path(KEY_DISPLAY_NAME).isMissingNode()
                        && answers.path(KEY_PRIMARY_COLOR).isMissingNode()
                        && answers.path(KEY_ACCENT_COLOR).isMissingNode()
        ) {
            return null;
        }
        return new OnboardingAnswersDto(
                textOrNull(answers.path(KEY_BRANCH_COUNT)),
                localities.isEmpty() ? null : localities,
                storeTypes.isEmpty() ? null : storeTypes.get(0),
                storeTypes.isEmpty() ? null : storeTypes,
                departments.isEmpty() ? null : departments,
                textOrNull(answers.path(KEY_ONLINE_STORE)),
                textOrNull(answers.path(KEY_DISPLAY_NAME)),
                textOrNull(answers.path(KEY_PRIMARY_COLOR)),
                textOrNull(answers.path(KEY_ACCENT_COLOR))
        );
    }

    private static List<String> readStoreTypes(JsonNode answers) {
        List<String> storeTypes = readStringList(answers.path(KEY_STORE_TYPES));
        if (!storeTypes.isEmpty()) {
            return storeTypes;
        }
        String legacy = textOrNull(answers.path(KEY_STORE_TYPE));
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

    private void applyAnswersPatch(ObjectNode answers, OnboardingAnswersDto patch) {
        if (patch.branchCount() != null) {
            answers.put(KEY_BRANCH_COUNT, patch.branchCount().trim());
        }
        if (patch.branchLocalities() != null) {
            answers.set(KEY_BRANCH_LOCALITIES, toArray(patch.branchLocalities()));
        }
        if (patch.storeTypes() != null) {
            answers.set(KEY_STORE_TYPES, toArray(patch.storeTypes()));
            if (!patch.storeTypes().isEmpty()) {
                answers.put(KEY_STORE_TYPE, patch.storeTypes().get(0).trim());
            }
        } else if (patch.storeType() != null) {
            String storeType = patch.storeType().trim();
            answers.put(KEY_STORE_TYPE, storeType);
            answers.set(KEY_STORE_TYPES, toArray(List.of(storeType)));
        }
        if (patch.selectedDepartments() != null) {
            answers.set(KEY_SELECTED_DEPARTMENTS, toArray(patch.selectedDepartments()));
        }
        if (patch.onlineStore() != null) {
            answers.put(KEY_ONLINE_STORE, patch.onlineStore().trim());
        }
        if (patch.displayName() != null) {
            answers.put(KEY_DISPLAY_NAME, patch.displayName().trim());
        }
        if (patch.primaryColor() != null) {
            answers.put(KEY_PRIMARY_COLOR, patch.primaryColor().trim());
        }
        if (patch.accentColor() != null) {
            answers.put(KEY_ACCENT_COLOR, patch.accentColor().trim());
        }
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

    private static String textOrDefault(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return value == null ? fallback : value;
    }

    private static Instant instantOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
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
                    "Could not save onboarding settings"
            );
        }
    }
}
