package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Unified product activity row (audit event or stock ledger movement).
 */
public record ItemTimelineEntryResponse(
        String id,
        String kind,
        String eventType,
        String title,
        String summary,
        String actorName,
        String branchId,
        String source,
        BigDecimal quantityDelta,
        String referenceType,
        String referenceId,
        String metadata,
        Instant createdAt
) {
}
