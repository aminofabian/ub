package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupplierPortalTeamUserRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 191) String email,
        @Size(max = 32) String phone,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 32) String roleKey
) {
}
