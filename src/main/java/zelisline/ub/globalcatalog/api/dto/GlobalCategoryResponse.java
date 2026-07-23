package zelisline.ub.globalcatalog.api.dto;

public record GlobalCategoryResponse(
        String id,
        String name,
        String slug,
        int position,
        String tenantCategorySlugHint,
        String parentId
) {
}
