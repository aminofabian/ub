package zelisline.ub.audit.api;

import java.time.Instant;

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
import zelisline.ub.audit.application.AuditEventQueryService;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
public class AuditEventsController {

    private final AuditEventQueryService queryService;

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
                filter.severity(),
                filter.actorId(),
                filter.targetType(),
                filter.targetId(),
                filter.shiftId(),
                filter.from(),
                filter.to(),
                pageable
        );

        return page.map(this::toResponse);
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

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getBusinessId(),
                e.getBranchId(),
                e.getCategory(),
                e.getEventType(),
                e.getSeverity(),
                e.getActorId(),
                e.getActorType(),
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
}
