package zelisline.ub.till.api.dto;

import java.time.Instant;

public record TillDeviceResponse(
        String id,
        String branchId,
        String deviceKey,
        String label,
        String cashierTemplate,
        String registeredBy,
        Instant registeredAt,
        Instant revokedAt
) {
}
