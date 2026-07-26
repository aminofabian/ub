package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetSupplierPortalTeamUserPasswordRequest(
        @NotBlank @Size(min = 8, max = 128) String password
) {
}
