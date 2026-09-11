package zelisline.ub.credits.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Owner-pinned tags on a customer (warehouse scope §8.4). Stored on
 * {@code customers.tags} as a JSON array string so raw-SQL surfaces (Shoppers
 * spend rows, campaign audiences) can match a tag without a join.
 */
public final class CustomerTags {

    public static final String WHOLESALE = "wholesale";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };
    private static final int MAX_TAGS = 16;
    private static final int MAX_TAG_LENGTH = 64;

    private CustomerTags() {
    }

    /** Null-safe parse; invalid or blank JSON reads as "no tags". */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = MAPPER.readValue(json, LIST);
            return tags == null ? List.of() : List.copyOf(tags);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of();
        }
    }

    /** Trimmed, de-duplicated (case-insensitive), capped; empty list → null (column stays NULL). */
    public static String serialize(List<String> tags) {
        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static List<String> normalize(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : tags) {
            if (raw == null) {
                continue;
            }
            String tag = raw.trim();
            if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH) {
                continue;
            }
            String key = tag.toLowerCase();
            if (seen.add(key)) {
                out.add(tag);
            }
            if (out.size() >= MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Whether a stored tags JSON contains the tag (case-insensitive). */
    public static boolean has(String tagsJson, String tag) {
        if (tagsJson == null || tagsJson.isBlank() || tag == null || tag.isBlank()) {
            return false;
        }
        String needle = tag.trim().toLowerCase();
        for (String candidate : parse(tagsJson)) {
            if (candidate.toLowerCase().equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
