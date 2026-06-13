package zelisline.ub.posdraft.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.posdraft.api.dto.CancelPosDraftRequest;
import zelisline.ub.posdraft.api.dto.CompletePosDraftRequest;
import zelisline.ub.posdraft.api.dto.CompletePosDraftResponse;
import zelisline.ub.posdraft.api.dto.CreatePosDraftRequest;
import zelisline.ub.posdraft.api.dto.PatchPosDraftLinesRequest;
import zelisline.ub.posdraft.api.dto.PosDraftListResponse;
import zelisline.ub.posdraft.api.dto.PosDraftResponse;
import zelisline.ub.posdraft.api.dto.PutPosDraftLineRequest;
import zelisline.ub.posdraft.application.PosDraftService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/pos-drafts")
@RequiredArgsConstructor
public class PosDraftController {

    private final PosDraftService service;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService requestPermissionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'pos.drafts.write')")
    public ResponseEntity<PosDraftResponse> createDraft(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePosDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        String clientDraftId = resolveClientDraftId(body.clientDraftId(), idempotencyKey);
        CreatePosDraftRequest safe = new CreatePosDraftRequest(validatedBranch, clientDraftId, body.lines());
        PosDraftResponse response = service.createDraft(businessId, safe, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'pos.drafts.read')")
    public PosDraftResponse getDraft(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        return service.getDraft(TenantRequestIds.resolveBusinessId(request), id, includeDeleted);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'pos.drafts.read')")
    public PosDraftListResponse listDrafts(
            @RequestParam String branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) Integer hoursBack,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), branchId);
        String createdByFilter = createdBy != null && !createdBy.isBlank()
                ? createdBy.trim()
                : null;
        return service.listDrafts(businessId, validatedBranch, status, createdByFilter, hoursBack);
    }

    @PatchMapping("/{id}/lines")
    @PreAuthorize("hasPermission(null, 'pos.drafts.write')")
    public PosDraftResponse patchLines(
            @PathVariable String id,
            @Valid @RequestBody PatchPosDraftLinesRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.patchLines(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body,
                principal.userId()
        );
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'pos.drafts.write')")
    public PosDraftResponse putLine(
            @PathVariable String id,
            @PathVariable String lineId,
            @Valid @RequestBody PutPosDraftLineRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.putLine(
                TenantRequestIds.resolveBusinessId(request),
                id,
                lineId,
                body,
                principal.userId()
        );
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'pos.drafts.write')")
    public PosDraftResponse deleteLine(
            @PathVariable String id,
            @PathVariable String lineId,
            @RequestParam(required = false) Long expectedVersion,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return service.deleteLine(
                TenantRequestIds.resolveBusinessId(request),
                id,
                lineId,
                expectedVersion,
                principal.userId()
        );
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public ResponseEntity<CompletePosDraftResponse> completeDraft(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @jakarta.validation.constraints.NotBlank String idempotencyKey,
            @Valid @RequestBody CompletePosDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        CompletePosDraftResponse response = service.completeDraft(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body,
                idempotencyKey,
                principal.userId()
        );
        HttpStatus status = response.createdNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'pos.drafts.cancel.own') or hasPermission(null, 'pos.drafts.cancel.any')")
    public PosDraftResponse cancelDraft(
            @PathVariable String id,
            @Valid @RequestBody(required = false) CancelPosDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        PosDraftResponse existing = service.getDraft(businessId, id, false);
        enforceCancelAccess(principal, existing);
        return service.cancelDraft(
                businessId,
                id,
                body == null ? new CancelPosDraftRequest(null) : body,
                principal.userId()
        );
    }

    private void enforceCancelAccess(TenantPrincipal principal, PosDraftResponse draft) {
        boolean canCancelAny = requestPermissionService.hasPermission(
                principal.roleId(), "pos.drafts.cancel.any");
        if (canCancelAny) {
            return;
        }
        String creator = draft.createdBy();
        if (creator == null || !creator.equals(principal.userId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Draft not found");
        }
    }

    private static String resolveClientDraftId(String bodyClientDraftId, String headerKey) {
        if (bodyClientDraftId != null && !bodyClientDraftId.isBlank()) {
            return bodyClientDraftId.trim();
        }
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "clientDraftId or Idempotency-Key is required");
    }
}
