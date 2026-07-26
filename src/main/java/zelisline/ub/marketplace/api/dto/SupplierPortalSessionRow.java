package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalSessionRow(
        String sessionId,
        String ip,
        String userAgent,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        boolean current,
        boolean revoked
) {
}
