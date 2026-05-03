package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogItemDetailResponse(
        String id,
        String name,
        String description,
        String variantName,
        String parentItemId,
        String currency,
        BigDecimal price,
        List<PublicItemImageResponse> images,
        List<PublicCatalogVariantResponse> variants
) {
}
