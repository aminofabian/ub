package zelisline.ub.storefront.api.dto;

import java.util.List;

public record PublicCategoryListResponse(
        List<PublicCategoryResponse> categories
) {
}
