package zelisline.ub.audit.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.domain.AuditEventPayload;

/**
 * Persists audit events after the business transaction commits.
 *
 * <p>Running after commit keeps the audit write off the critical path of the business
 * transaction and prevents an audit-write failure from rolling back business work.
 */
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditEventPublisher auditEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuditEvent(AuditEventPayload payload) {
        auditEventPublisher.persist(payload);
    }
}
