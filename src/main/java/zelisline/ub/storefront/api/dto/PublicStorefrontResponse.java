package zelisline.ub.storefront.api.dto;

import java.util.List;

public record PublicStorefrontResponse(
        String businessName,
        String slug,
        String currency,
        String catalogBranchId,
        String catalogBranchName,
        String label,
        String announcement,
        List<PublicCatalogItemCardResponse> featured
) {
}
