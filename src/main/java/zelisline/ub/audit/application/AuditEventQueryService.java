package zelisline.ub.audit.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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

    /** Upper bound on {@code eventTypes} so a client cannot send an unbounded IN list. */
    private static final int MAX_EVENT_TYPES = 64;

    private final AuditEventRepository auditEventRepository;

    public Page<AuditEvent> search(
            String businessId,
            String branchId,
            AuditEventCategory category,
            String eventType,
            String eventTypes,
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
        List<String> types = eventTypeList(eventType, eventTypes);
        return auditEventRepository.search(
                businessId,
                branchId,
                category,
                types.isEmpty(),
                types.isEmpty() ? List.of("*") : types,
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
     * Period totals by severity, category and event type, honoring the same
     * filters as {@link #search} so header counts match the visible list.
     */
    public AuditEventSummaryResponse summarize(
            String businessId,
            String branchId,
            AuditEventCategory category,
            String eventType,
            String eventTypes,
            AuditEventSeverity severity,
            AuditEventSeverity minSeverity,
            Instant from,
            Instant to
    ) {
        List<String> types = eventTypeList(eventType, eventTypes);
        List<Object[]> rows = auditEventRepository.countBySeverityCategoryAndType(
                businessId,
                branchId,
                category,
                types.isEmpty(),
                types.isEmpty() ? List.of("*") : types,
                severityRange(severity, minSeverity),
                from,
                to
        );
        Map<AuditEventSeverity, Long> bySeverity = new EnumMap<>(AuditEventSeverity.class);
        Map<AuditEventCategory, Long> byCategory = new EnumMap<>(AuditEventCategory.class);
        Map<String, Long> byEventType = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : rows) {
            AuditEventSeverity sev = (AuditEventSeverity) row[0];
            AuditEventCategory cat = (AuditEventCategory) row[1];
            String type = (String) row[2];
            long count = ((Number) row[3]).longValue();
            bySeverity.merge(sev, count, Long::sum);
            byCategory.merge(cat, count, Long::sum);
            byEventType.merge(type, count, Long::sum);
            total += count;
        }
        return new AuditEventSummaryResponse(total, bySeverity, byCategory, byEventType);
    }

    /**
     * An exact {@code eventType} wins; otherwise {@code eventTypes} is split on
     * commas, trimmed, de-duplicated and capped. Empty means "any type".
     */
    static List<String> eventTypeList(String eventType, String eventTypes) {
        if (eventType != null && !eventType.isBlank()) {
            return List.of(eventType.trim());
        }
        if (eventTypes == null || eventTypes.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : eventTypes.split(",")) {
            String t = raw.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
            if (out.size() >= MAX_EVENT_TYPES) {
                break;
            }
        }
        return out;
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
