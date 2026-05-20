package zelisline.ub.payments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for super admin to enable/disable or update a platform gateway.
 */
public record PlatformGatewayRequest(
        boolean isEnabled,

        Boolean supplierPayoutSupported,

        @NotBlank
        @Size(max = 100)
        String displayName,

        @Size(max = 10_000)
        String description,

        @Size(max = 255)
        String logoUrl,

        int sortOrder
) {
}
