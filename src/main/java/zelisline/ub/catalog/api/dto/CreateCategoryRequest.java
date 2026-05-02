package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 36) String parentId,
        @Size(max = 500) String icon,
        Integer position
) {
}
