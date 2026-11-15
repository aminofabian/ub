package zelisline.ub.restaurant.application;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;

/**
 * The modifier options chosen on a line, stored with the line as JSON so that renaming or
 * removing an option later never changes what was sold or what the kitchen was told.
 */
final class ModifierSnapshots {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChosenModifier>> SHAPE = new TypeReference<>() { };

    private ModifierSnapshots() {
    }

    static String encode(List<ChosenModifier> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(chosen);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the chosen modifiers", e);
        }
    }

    /** Unreadable or empty text reads as no modifiers, so one bad row cannot break a kitchen board. */
    static List<ChosenModifier> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, SHAPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
