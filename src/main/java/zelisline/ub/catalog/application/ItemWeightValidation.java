package zelisline.ub.catalog.application;

import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

/**
 * Validates weight-related consistency on catalog items.
 */
public final class ItemWeightValidation {

    private static final Set<String> WEIGHT_UNITS = Set.of("kg", "g", "lb");

    private ItemWeightValidation() {
    }

    /**
     * Ensures that weighed items use a supported weight unit.
     *
     * @throws ResponseStatusException with {@code 400 BAD_REQUEST} if invalid
     */
    public static void validate(Item item) {
        if (!item.isWeighed()) {
            return;
        }
        String unit = item.getUnitType();
        if (unit == null || unit.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Weighed item must have a unit type");
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        if (!WEIGHT_UNITS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Weighed item unit type must be one of: kg, g, lb");
        }
    }
}
