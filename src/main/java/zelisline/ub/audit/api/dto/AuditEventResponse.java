package zelisline.ub.audit.api.dto;

import java.time.Instant;

import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

public record AuditEventResponse(
        String id,
        String businessId,
        String branchId,
        AuditEventCategory category,
        String eventType,
        AuditEventSeverity severity,
        String actorId,
        AuditEventActorType actorType,
        String actorName,
        String targetType,
        String targetId,
        String targetLabel,
        String sessionId,
        String correlationId,
        String ipAddress,
        String userAgent,
        String source,
        String terminalId,
        String shiftId,
        String oldState,
        String newState,
        String diff,
        String reason,
        String metadata,
        Instant createdAt
) {
}
