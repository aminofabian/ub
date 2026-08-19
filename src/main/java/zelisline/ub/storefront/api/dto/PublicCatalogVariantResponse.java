package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogVariantResponse(
        String id,
        String sku,
        String name,
        String variantName,
        String imageUrl,
        /** Final storefront price after applying any active catalog discounts. */
        BigDecimal price,
        /** Regular price before any active catalog discounts (null when no discount). */
        BigDecimal regularPrice,
        /** Amount saved versus regular price (after rounding). */
        BigDecimal savedAmount,
        /** Discount label shown for shoppers (e.g. "Weekend Sale"). */
        String discountName,
        BigDecimal qtyOnHand,
        String onlinePurchaseMode,
        boolean weighed,
        String unitType
) {
}
