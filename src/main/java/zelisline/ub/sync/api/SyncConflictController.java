package zelisline.ub.sync.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.sync.api.dto.ResolveLocalWinsRequest;
import zelisline.ub.sync.api.dto.SyncConflictResponse;
import zelisline.ub.sync.application.SyncConflictService;
import zelisline.ub.sync.domain.SyncConflict;
import zelisline.ub.tenancy.api.TenantRequestIds;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/sync/conflicts")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'business.manage_settings')")
public class SyncConflictController {

    private final SyncConflictService syncConflictService;

    @GetMapping
    public Page<SyncConflictResponse> listPending(Pageable pageable, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return syncConflictService.listPending(businessId, pageable)
                .map(SyncConflictController::toResponse);
    }

    @GetMapping("/count")
    public Map<String, Long> countPending(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return Map.of("pending", syncConflictService.countPending(businessId));
    }

    @PostMapping("/{conflictId}/resolve/server-wins")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveServerWins(@PathVariable String conflictId, HttpServletRequest request) {
        String userId = CurrentTenantUser.auditActorId(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        syncConflictService.resolveServerWins(businessId, conflictId, userId);
    }

    @PostMapping("/{conflictId}/resolve/local-wins")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveLocalWins(
            @PathVariable String conflictId,
            @Valid @RequestBody ResolveLocalWinsRequest body,
            HttpServletRequest request
    ) {
        String userId = CurrentTenantUser.auditActorId(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        // We need the entityType and entityId from the conflict, so fetch it first.
        // The service method can look it up internally.
        syncConflictService.resolveLocalWinsByConflictId(businessId, conflictId, userId, body.resolvedSnapshot());
    }

    static SyncConflictResponse toResponse(SyncConflict c) {
        return new SyncConflictResponse(
                c.getId(),
                c.getEntityType(),
                c.getEntityId(),
                c.getLocalVersion() != null ? c.getLocalVersion().toString() : null,
                c.getServerVersion() != null ? c.getServerVersion().toString() : null,
                c.getResolution(),
                c.getNotes(),
                c.getLocalSnapshot(),
                c.getServerSnapshot(),
                c.getCreatedBy(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getResolvedAt() != null ? c.getResolvedAt().toString() : null,
                c.getResolvedBy()
        );
    }
}
