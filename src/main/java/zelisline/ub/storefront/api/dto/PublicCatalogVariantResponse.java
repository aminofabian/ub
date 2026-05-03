package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogVariantResponse(
        String id,
        String name,
        String variantName,
        String imageUrl,
        BigDecimal price
) {
}
