package zelisline.ub.audit.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.api.dto.AuditEventFilterRequest;
import zelisline.ub.audit.api.dto.AuditEventResponse;
import zelisline.ub.audit.api.dto.AuditEventSummaryResponse;
import zelisline.ub.audit.application.AuditEventQueryService;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
public class AuditEventsController {

    private final AuditEventQueryService queryService;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'audit.read')")
    public Page<AuditEventResponse> list(
            @Valid AuditEventFilterRequest filter,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        // Cashiers and branch-scoped users only see their own branch unless they are business-wide.
        String branchId = effectiveBranchId(principal, filter.branchId());

        Pageable pageable = PageRequest.of(
                filter.page(),
                filter.size(),
                parseSort(filter.sort())
        );

        Page<AuditEvent> page = queryService.search(
                businessId,
                branchId,
                filter.category(),
                filter.eventType(),
                filter.eventTypes(),
                filter.severity(),
                filter.minSeverity(),
                filter.actorId(),
                filter.targetType(),
                filter.targetId(),
                filter.shiftId(),
                filter.from(),
                filter.to(),
                pageable
        );

        Map<String, String> names = staffNames(businessId, page.getContent());
        return page.map(e -> toResponse(e, names));
    }

    /**
     * Most emitters store only {@code actorId}; the diary reads sentences, so
     * resolve staff names for the page in one query. Scoped to the caller's
     * business: a foreign user id resolves to nothing.
     */
    private Map<String, String> staffNames(String businessId, List<AuditEvent> events) {
        Set<String> ids = new HashSet<>();
        for (AuditEvent e : events) {
            if (e.getActorType() == zelisline.ub.audit.domain.AuditEventActorType.USER
                    && e.getActorId() != null
                    && (e.getActorName() == null || e.getActorName().isBlank())) {
                ids.add(e.getActorId());
            }
            if ("user".equals(e.getTargetType()) && e.getTargetId() != null) {
                ids.add(e.getTargetId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            if (!businessId.equals(u.getBusinessId())) {
                continue;
            }
            String name = u.getName() != null && !u.getName().isBlank()
                    ? u.getName().trim()
                    : u.getEmail();
            if (name != null && !name.isBlank()) {
                names.put(u.getId(), name.trim());
            }
        }
        return names;
    }

    /** Period totals for the activity-log header (same filters as the list). */
    @GetMapping("/summary")
    @PreAuthorize("hasPermission(null, 'audit.read')")
    public AuditEventSummaryResponse summary(
            @Valid AuditEventFilterRequest filter,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String branchId = effectiveBranchId(principal, filter.branchId());

        return queryService.summarize(
                businessId,
                branchId,
                filter.category(),
                filter.eventType(),
                filter.eventTypes(),
                filter.severity(),
                filter.minSeverity(),
                filter.from(),
                filter.to()
        );
    }

    private String effectiveBranchId(TenantPrincipal principal, String requestedBranchId) {
        if (principal.branchId() != null && !principal.branchId().isBlank()) {
            return principal.branchId();
        }
        return requestedBranchId;
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        // Simple sort syntax: "createdAt,desc" or "createdAt,asc"
        String[] parts = sort.trim().split(",");
        if (parts.length == 1) {
            return Sort.by(Sort.Direction.DESC, parts[0]);
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }

    private AuditEventResponse toResponse(AuditEvent e, Map<String, String> names) {
        String actorName = e.getActorName();
        if ((actorName == null || actorName.isBlank()) && e.getActorId() != null) {
            actorName = names.get(e.getActorId());
        }
        String targetName = "user".equals(e.getTargetType()) && e.getTargetId() != null
                ? names.get(e.getTargetId())
                : null;
        return new AuditEventResponse(
                e.getId(),
                e.getBusinessId(),
                e.getBranchId(),
                e.getCategory(),
                e.getEventType(),
                e.getSeverity(),
                e.getActorId(),
                e.getActorType(),
                actorName,
                e.getTargetType(),
                e.getTargetId(),
                e.getTargetLabel(),
                targetName,
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
}
