package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDomainOrderRequest(
        @NotBlank @Size(max = 255) String fqdn
) {
}
