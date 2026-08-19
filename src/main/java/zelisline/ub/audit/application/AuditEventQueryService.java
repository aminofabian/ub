package zelisline.ub.audit.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.api.dto.AuditEventSummaryResponse;
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
            AuditEventSeverity minSeverity,
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
                severityRange(severity, minSeverity),
                actorId,
                targetType,
                targetId,
                shiftId,
                from,
                to,
                pageable
        );
    }

    /**
     * Period totals by severity and category, honoring the same filters as
     * {@link #search} so dashboard cards match the visible table.
     */
    public AuditEventSummaryResponse summarize(
            String businessId,
            String branchId,
            AuditEventCategory category,
            String eventType,
            AuditEventSeverity severity,
            AuditEventSeverity minSeverity,
            Instant from,
            Instant to
    ) {
        List<Object[]> rows = auditEventRepository.countBySeverityAndCategory(
                businessId,
                branchId,
                category,
                eventType,
                severityRange(severity, minSeverity),
                from,
                to
        );
        Map<AuditEventSeverity, Long> bySeverity = new EnumMap<>(AuditEventSeverity.class);
        Map<AuditEventCategory, Long> byCategory = new EnumMap<>(AuditEventCategory.class);
        long total = 0;
        for (Object[] row : rows) {
            AuditEventSeverity sev = (AuditEventSeverity) row[0];
            AuditEventCategory cat = (AuditEventCategory) row[1];
            long count = ((Number) row[2]).longValue();
            bySeverity.merge(sev, count, Long::sum);
            byCategory.merge(cat, count, Long::sum);
            total += count;
        }
        return new AuditEventSummaryResponse(total, bySeverity, byCategory);
    }

    /**
     * An exact {@code severity} wins; otherwise a non-null {@code minSeverity}
     * expands to everything at or above it (e.g. WARN → WARN/ERROR/CRITICAL);
     * otherwise all severities. Never returns null so the JPQL {@code IN} stays
     * well-formed.
     */
    private static List<AuditEventSeverity> severityRange(
            AuditEventSeverity exact,
            AuditEventSeverity min
    ) {
        if (exact != null) {
            return List.of(exact);
        }
        List<AuditEventSeverity> all = Arrays.asList(AuditEventSeverity.values());
        if (min != null) {
            return all.subList(min.ordinal(), all.size());
        }
        return all;
    }
}
