package zelisline.ub.sales.api.dto;

import java.time.Instant;

/**
 * A single audit trail entry for a shift.
 */
public record ShiftAuditEntryResponse(
        String id,
        String eventType,
        String performedBy,
        String performedByName,
        String metadata,
        String ipAddress,
        Instant createdAt
) {
}
