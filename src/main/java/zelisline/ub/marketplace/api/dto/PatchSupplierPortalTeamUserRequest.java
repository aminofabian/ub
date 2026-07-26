package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Size;

public record PatchSupplierPortalTeamUserRequest(
        @Size(max = 32) String roleKey,
        Boolean active
) {
}
