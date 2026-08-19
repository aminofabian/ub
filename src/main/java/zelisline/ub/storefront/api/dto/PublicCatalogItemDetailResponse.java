package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogItemDetailResponse(
        String id,
        String sku,
        String name,
        String description,
        String variantName,
        String parentItemId,
        String currency,
        /** Final storefront price after applying any active catalog discounts. */
        BigDecimal price,
        /** Regular price before any active catalog discounts (null when no discount). */
        BigDecimal regularPrice,
        /** Amount saved versus regular price (after rounding). */
        BigDecimal savedAmount,
        /** Discount label shown for shoppers (e.g. "Weekend Sale"). */
        String discountName,
        BigDecimal qtyOnHand,
        List<PublicItemImageResponse> images,
        List<PublicCatalogVariantResponse> variants,
        String onlinePurchaseMode,
        boolean weighed,
        String unitType
) {
}
