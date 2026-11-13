package zelisline.ub.serviceops.api.dto;

import jakarta.validation.constraints.Size;

public record PatchServiceCategoryRequest(
        @Size(max = 191) String name,
        Integer sortOrder,
        Boolean active
) {
}
