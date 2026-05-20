package zelisline.ub.payments.api.dto;

import java.time.Instant;

/**
 * Response DTO for a tenant gateway configuration.
 * Credentials are never returned — clients only see redacted metadata.
 */
public record GatewayConfigResponse(
        String id,
        String businessId,
        String gatewayType,
        String label,
        String status,
        boolean isDefault,
        Instant lastTestedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
