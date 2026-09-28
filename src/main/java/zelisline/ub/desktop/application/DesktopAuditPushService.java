package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.repository.AuditEventRepository;
import zelisline.ub.desktop.api.dto.AuditEventPushAck;
import zelisline.ub.desktop.api.dto.AuditEventPushRequest;

/**
 * Desktop-side forward of the till's local audit events to the shop's cloud
 * audit log — the "up" direction, alongside {@link DesktopSyncPushService}.
 *
 * <p>Pages the till's {@code audit_events} with a {@code (createdAt, id)} cursor
 * (so equal timestamps never stall or skip — see {@link DesktopAuditCursor}) and
 * advances the cursor only after the cloud acknowledges. The cloud ingest is
 * idempotent by event id, so the boundary re-send is harmless.
 *
 * <p>On first run (no cursor file) the cursor defaults to epoch, so an existing
 * till backfills its whole local audit history to the cloud once — over
 * successive batches, bounded by {@link #BATCH_MAX} per flush. A brand-new till
 * has almost no history, so this is cheap for it too.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopAuditPushService {

    private static final Logger log = LoggerFactory.getLogger(DesktopAuditPushService.class);

    /** Cap on events per up-stream request (mirrors the other push streams). */
    private static final int BATCH_MAX = 200;

    /**
     * Where a till with no cursor file starts from: the beginning of its local
     * audit log, so existing history is forwarded (backfill) rather than lost.
     * The cursor is only persisted once the cloud acknowledges a batch, so a
     * failed backfill simply resumes from the same point.
     */
    private static final DesktopAuditCursor.Cursor BACKFILL_FROM =
        new DesktopAuditCursor.Cursor(Instant.EPOCH, "");

    private final AuditEventRepository auditEventRepository;
    private final CloudSyncSession cloudSyncSession;
    private final DesktopAuditCursor auditCursor;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    /**
     * Forward the till's local audit events the cloud has not seen yet.
     *
     * @return the number of events the cloud newly accepted (duplicates skipped)
     */
    public int pushPendingAudits() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession.load().orElse(null);
        if (mapping == null || localId.isEmpty()) {
            return 0;
        }

        DesktopAuditCursor.Cursor cursor = auditCursor.load().orElse(BACKFILL_FROM);

        List<AuditEvent> batch = auditEventRepository.findAfterCursor(
            localId, cursor.createdAt(), cursor.id(), PageRequest.of(0, BATCH_MAX));
        if (batch.isEmpty()) {
            return 0;
        }

        AuditEventPushRequest request = new AuditEventPushRequest(
            batch.stream().map(DesktopAuditPushService::toData).toList());
        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        AuditEventPushAck ack = post(client, mapping, request);

        AuditEvent last = batch.get(batch.size() - 1);
        auditCursor.save(new DesktopAuditCursor.Cursor(last.getCreatedAt(), last.getId()));
        log.info(
            "[DesktopSync] forwarded {} till audit event(s) to {} ({} new, {} duplicate)",
            batch.size(), mapping.origin(), ack.ingested(), ack.skipped());
        return ack.ingested();
    }

    private AuditEventPushAck post(
            RestClient client,
            CloudSyncSession.Session session,
            AuditEventPushRequest batch) {
        try {
            return doPost(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not forward audit events to the online shop (" + e.getMessage() + ")");
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, session)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect");
            }
            return doPost(client, refreshed, batch);
        }
    }

    private AuditEventPushAck doPost(
            RestClient client,
            CloudSyncSession.Session session,
            AuditEventPushRequest batch) {
        AuditEventPushAck ack = client
            .post()
            .uri("/api/v1/desktop/sync/audit-events")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(AuditEventPushAck.class);
        if (ack == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty acknowledgment");
        }
        return ack;
    }

    private static AuditEventPushRequest.AuditEventData toData(AuditEvent e) {
        return new AuditEventPushRequest.AuditEventData(
            e.getId(),
            e.getBranchId(),
            e.getCategory() == null ? null : e.getCategory().name(),
            e.getEventType(),
            e.getSeverity() == null ? null : e.getSeverity().name(),
            e.getActorId(),
            e.getActorType() == null ? null : e.getActorType().name(),
            e.getActorName(),
            e.getTargetType(),
            e.getTargetId(),
            e.getTargetLabel(),
            e.getSessionId(),
            e.getCorrelationId(),
            e.getIpAddress(),
            e.getUserAgent(),
            e.getSource(),
            e.getTerminalId(),
            e.getShiftId(),
            e.getOldState(),
            e.getNewState(),
            e.getDiff(),
            e.getReason(),
            e.getMetadata(),
            e.getCreatedAt()
        );
    }

    private static boolean isUnauthorized(Exception e) {
        // Structural status beats message matching — same rule as DesktopSyncPushService.
        if (e instanceof org.springframework.web.client.RestClientResponseException r) {
            return r.getStatusCode().value() == 401;
        }
        if (e instanceof ResponseStatusException rse) {
            return rse.getStatusCode().value() == 401;
        }
        String message = e.getMessage();
        return message != null && (message.contains("401") || message.contains("Unauthorized"));
    }
}
