package zelisline.ub.catalog.api.dto;

public record CategoryResponse(
        String id,
        String name,
        String slug,
        int position,
        String icon,
        String parentId,
        boolean active
) {
}
