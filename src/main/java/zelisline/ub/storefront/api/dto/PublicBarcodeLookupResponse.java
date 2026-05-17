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
        List<PublicItemImageResponse> images
) {
}
