package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.Size;

public record PatchAisleRequest(
        @Size(max = 255) String name,
        @Size(max = 191) String code,
        Integer sortOrder,
        Boolean active
) {
}
