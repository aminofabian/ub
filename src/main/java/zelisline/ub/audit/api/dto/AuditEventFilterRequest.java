package zelisline.ub.audit.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

public record AuditEventFilterRequest(
        String branchId,
        AuditEventCategory category,
        String eventType,
        AuditEventSeverity severity,
        String actorId,
        String targetType,
        String targetId,
        String shiftId,
        Instant from,
        Instant to,
        @Min(0) Integer page,
        @Min(1) @Max(200) Integer size,
        String sort
) {
    public AuditEventFilterRequest {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
    }
}
