package zelisline.ub.catalog.api.dto;

import java.util.List;

public record CategoryTreeNodeResponse(
        String id,
        String name,
        String slug,
        String parentId,
        int position,
        int depth,
        boolean active,
        String thumbnailUrl,
        String description,
        int childCount,
        List<CategoryTreeNodeResponse> children
) {
}
