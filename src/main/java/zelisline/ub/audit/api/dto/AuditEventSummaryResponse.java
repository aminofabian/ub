package zelisline.ub.audit.api.dto;

import java.util.Map;

import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

/**
 * Period totals for the activity-log header cards. Counts respect the same
 * filters as {@code GET /api/v1/audit-events} (branch, category, event type,
 * severity range, time range) so the cards agree with the visible table.
 */
public record AuditEventSummaryResponse(
        long total,
        Map<AuditEventSeverity, Long> bySeverity,
        Map<AuditEventCategory, Long> byCategory
) {
}
