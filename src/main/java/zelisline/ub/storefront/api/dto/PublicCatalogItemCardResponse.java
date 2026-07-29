package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogItemCardResponse(
        String id,
        String sku,
        String name,
        String variantName,
        String imageUrl,
        BigDecimal price,
        /** On-hand quantity at the storefront catalog branch (active inventory batches). */
        BigDecimal qtyOnHand,
        /** Latest buying price across all suppliers (most recent effectiveFrom). */
        BigDecimal buyingPrice,
        /** {@link zelisline.ub.storefront.application.StorefrontOnlinePurchaseRules#WEB_CART}. */
        String onlinePurchaseMode,
        /** When true, shoppers may order fractional quantities (kg / weight). */
        boolean weighed,
        /** Sale unit label (e.g. kg, each). */
        String unitType
) {
}
