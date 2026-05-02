package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAisleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 191) String code,
        Integer sortOrder
) {
}
