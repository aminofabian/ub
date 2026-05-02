package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBusinessRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 191) @Pattern(regexp = "^[a-zA-Z0-9-]+$") String slug,
        @Size(min = 3, max = 3) String currency,
        @Size(min = 2, max = 2) String countryCode,
        @Size(max = 100) String timezone,
        @Size(max = 64) String subscriptionTier,
        @Size(max = 255) String primaryDomain
) {
}
