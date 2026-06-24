package zelisline.ub.audit.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.audit.repository.AuditEventRepository;

/**
 * Entry point for emitting audit events.
 *
 * <p>{@link #publish(AuditEventPayload)} should be used from within business transactions.
 * The companion {@link AuditEventListener} persists the row after the transaction commits,
 * so the audit write cannot cause the business operation to roll back.
 *
 * <p>{@link #publishSynchronous(AuditEventPayload)} writes immediately and should be used
 * for events that occur outside a business transaction, such as a failed login attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final AuditEventRepository auditEventRepository;

    public void publish(AuditEventPayload payload) {
        eventPublisher.publishEvent(payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSynchronous(AuditEventPayload payload) {
        persist(payload);
    }

    void persist(AuditEventPayload payload) {
        try {
            AuditEvent event = toEntity(payload);
            auditEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to persist audit event {} for business {}",
                    payload.eventType(), payload.businessId(), e);
        }
    }

    private static AuditEvent toEntity(AuditEventPayload p) {
        AuditEvent e = new AuditEvent();
        e.setBusinessId(p.businessId());
        e.setBranchId(p.branchId());
        e.setCategory(p.category());
        e.setEventType(p.eventType());
        e.setSeverity(p.severity());
        e.setActorId(p.actorId());
        e.setActorType(p.actorType());
        e.setActorName(p.actorName());
        e.setTargetType(p.targetType());
        e.setTargetId(p.targetId());
        e.setTargetLabel(p.targetLabel());
        e.setSessionId(p.sessionId());
        e.setCorrelationId(p.correlationId());
        e.setIpAddress(p.ipAddress());
        e.setUserAgent(p.userAgent());
        e.setSource(p.source());
        e.setTerminalId(p.terminalId());
        e.setShiftId(p.shiftId());
        e.setOldState(p.oldState());
        e.setNewState(p.newState());
        e.setDiff(p.diff());
        e.setReason(p.reason());
        e.setMetadata(p.metadata());
        e.setCreatedAt(p.createdAt());
        return e;
    }
}
