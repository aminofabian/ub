package zelisline.ub.audit.domain;

import java.time.Instant;

import lombok.Builder;

/**
 * Lightweight, immutable payload published by services and persisted by {@link
 * zelisline.ub.audit.application.AuditEventListener}.
 */
@Builder
public record AuditEventPayload(
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

    public AuditEventPayload {
        if (businessId == null || businessId.isBlank()) {
            throw new IllegalArgumentException("businessId is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        if (actorType == null) {
            throw new IllegalArgumentException("actorType is required");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
