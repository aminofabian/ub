package zelisline.ub.payments.api.dto;

import java.time.Instant;

/**
 * Response for super admin platform gateway CRUD.
 */
public record PlatformGatewayResponse(
        String gatewayType,
        boolean isEnabled,
        boolean supplierPayoutSupported,
        String displayName,
        String description,
        String logoUrl,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
