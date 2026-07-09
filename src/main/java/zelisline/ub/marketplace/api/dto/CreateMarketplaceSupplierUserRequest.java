package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMarketplaceSupplierUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(min = 8, max = 128) String password
) {
}
