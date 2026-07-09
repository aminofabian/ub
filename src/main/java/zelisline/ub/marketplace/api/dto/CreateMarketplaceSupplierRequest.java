package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMarketplaceSupplierRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String description,
        @Email @Size(max = 255) String contactEmail,
        @Size(max = 32) String contactPhone
) {
}
