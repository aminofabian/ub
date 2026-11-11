package zelisline.ub.audit.api.dto;

import java.util.Map;

import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

/**
 * Period totals for the activity-log header. Counts respect the same filters
 * as {@code GET /api/v1/audit-events} (branch, category, event type, severity
 * range, time range) so the header agrees with the visible list.
 *
 * <p>{@code byEventType} lets the tenant diary draw attention chips (voids,
 * failed logins, cleared sales, ...) without a request per event type.
 */
public record AuditEventSummaryResponse(
        long total,
        Map<AuditEventSeverity, Long> bySeverity,
        Map<AuditEventCategory, Long> byCategory,
        Map<String, Long> byEventType
) {
}
