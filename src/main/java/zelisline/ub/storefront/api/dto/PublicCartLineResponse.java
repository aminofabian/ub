package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCartLineResponse(
        String itemId,
        String sku,
        String name,
        String variantName,
        String imageUrl,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        boolean weighed,
        String unitType,
        /** The shelf price, present only when this quantity earned a lower one. */
        BigDecimal regularUnitPrice,
        /** The least that may be ordered of this product, when the shop set one. */
        BigDecimal minOrderQty
) {
}
