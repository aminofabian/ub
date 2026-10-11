package zelisline.ub.catalog.application;

import java.math.BigDecimal;
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
     * Ensures that weighed items use a supported weight unit, and that pack /
     * shared-stock SKUs are never marked weighed (kg × unitsPerSale would
     * book a spurious stock loss).
     *
     * @throws ResponseStatusException with {@code 400 BAD_REQUEST} if invalid
     */
    public static void validate(Item item) {
        if (!item.isWeighed()) {
            return;
        }
        if (isPackSellSku(item)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This product sells as a pack, not by weight. Mark the base product as weighed, or use a separate weighed SKU.");
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

    /** True for package variants and other shared-stock pack SKUs. */
    public static boolean isPackSellSku(Item item) {
        if (item == null) {
            return false;
        }
        if (item.isPackageVariant()) {
            return true;
        }
        String parentId = item.getVariantOfItemId();
        if (parentId == null || parentId.isBlank()) {
            return false;
        }
        BigDecimal qty = item.getPackagingUnitQty();
        if (qty == null || qty.signum() <= 0) {
            return false;
        }
        if (!item.isStocked()) {
            return true;
        }
        return qty.compareTo(BigDecimal.ONE) > 0;
    }
}
