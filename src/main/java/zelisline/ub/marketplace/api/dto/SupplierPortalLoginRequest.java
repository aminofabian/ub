package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login with phone or email + PIN or password. */
public record SupplierPortalLoginRequest(
        @NotBlank @Size(max = 191) String identifier,
        @NotBlank String password
) {
}
