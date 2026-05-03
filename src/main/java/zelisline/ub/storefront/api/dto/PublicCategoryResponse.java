package zelisline.ub.storefront.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCategoryResponse(
        String id,
        String name,
        String parentId,
        String slug
) {
}
