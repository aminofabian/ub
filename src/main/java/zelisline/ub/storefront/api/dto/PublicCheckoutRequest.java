package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicCheckoutRequest(
        @NotBlank @Size(max = 255) String customerName,
        @NotBlank @Size(max = 64) String customerPhone,
        @Size(max = 255) String customerEmail,
        @Size(max = 2000) String notes
) {}
