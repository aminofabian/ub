package zelisline.ub.audit.application;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.audit.repository.AuditEventRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;

    public Page<AuditEvent> search(
            String businessId,
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
            Pageable pageable
    ) {
        return auditEventRepository.search(
                businessId,
                branchId,
                category,
                eventType,
                severity,
                actorId,
                targetType,
                targetId,
                shiftId,
                from,
                to,
                pageable
        );
    }
}
