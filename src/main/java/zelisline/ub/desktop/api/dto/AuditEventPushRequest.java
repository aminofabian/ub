package zelisline.ub.desktop.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Till → cloud forward of the till's local audit events, so the shop's cloud
 * audit log stays complete for a shop that also sells online (see
 * {@code DesktopSyncIngestService#ingestAuditEvents}).
 *
 * <p>The enum-typed fields are carried as strings so a till running an older or
 * newer build than the cloud can never fail the whole batch on an unknown
 * value — the cloud skips rows it cannot parse instead.
 */
public record AuditEventPushRequest(List<AuditEventData> events) {

    public record AuditEventData(
            String id,
            String branchId,
            String category,
            String eventType,
            String severity,
            String actorId,
            String actorType,
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
            Instant createdAt) {}
}
