package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateBusinessRequest(
        @Size(max = 255) String name,
        @Size(max = 64) String subscriptionTier,
        Boolean active
) {
}
