package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.Size;

public record PatchCategoryRequest(
        @Size(max = 500) String name,
        @Size(max = 191) String slug,
        @Size(max = 36) String parentId,
        Boolean active,
        Integer position,
        @Size(max = 500) String icon
) {
}
