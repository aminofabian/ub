package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standalone barcode lookup — no tenant/slug required.
 * Returns the first matching published product across all businesses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicBarcodeLookupResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String description,
        String brand,
        String size,
        String businessName,
        String businessSlug,
        String currency,
        BigDecimal price,
        BigDecimal qtyOnHand,
        List<PublicItemImageResponse> images,
        /** When this item is a variant, the parent item's name. {@code null} for standalone items. */
        String parentName,
        /** The variant's own label (e.g. "500ml", "1L"). {@code null} for standalone items. */
        String variantName,
        /** Scale PLU when resolved from a variable-weight label. */
        String pluCode,
        /** Parsed sell quantity in kg from a variable-weight label. */
        BigDecimal parsedWeightKg,
        /** Parsed line total from a price-embedded variable-weight label. */
        BigDecimal parsedLineTotal
) {
}
