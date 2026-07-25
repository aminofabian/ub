package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierPortalClaimCompleteRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(min = 32, max = 128) String setupToken,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 255) String name,
        @Size(max = 191) String email,
        @Size(min = 2, max = 64) String username
) {
}
