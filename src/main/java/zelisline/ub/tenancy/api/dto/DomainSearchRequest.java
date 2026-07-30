package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DomainSearchRequest(
        @NotBlank @Size(max = 255) String query
) {
}
