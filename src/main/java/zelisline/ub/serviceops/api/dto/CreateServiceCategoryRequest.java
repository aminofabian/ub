package zelisline.ub.serviceops.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServiceCategoryRequest(
        @NotBlank @Size(max = 191) String name,
        Integer sortOrder
) {
}
