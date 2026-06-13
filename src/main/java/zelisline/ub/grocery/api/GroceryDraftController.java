package zelisline.ub.grocery.api;

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
import zelisline.ub.grocery.api.dto.CancelGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.GroceryDraftListResponse;
import zelisline.ub.grocery.api.dto.GroceryDraftResponse;
import zelisline.ub.grocery.api.dto.IssueGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.IssueGroceryDraftResponse;
import zelisline.ub.grocery.api.dto.PatchGroceryDraftLinesRequest;
import zelisline.ub.grocery.api.dto.PutGroceryDraftLineRequest;
import zelisline.ub.grocery.application.GroceryDraftService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/grocery-drafts")
@RequiredArgsConstructor
public class GroceryDraftController {

    private final GroceryDraftService service;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService requestPermissionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public ResponseEntity<GroceryDraftResponse> createDraft(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateGroceryDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        String clientDraftId = resolveClientDraftId(body.clientDraftId(), idempotencyKey);
        CreateGroceryDraftRequest safe = new CreateGroceryDraftRequest(
                validatedBranch, clientDraftId, body.lines());
        GroceryDraftResponse response = service.createDraft(businessId, safe, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public GroceryDraftResponse getDraft(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        return service.getDraft(TenantRequestIds.resolveBusinessId(request), id, includeDeleted);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'grocery.invoices.read')")
    public GroceryDraftListResponse listDrafts(
            @RequestParam String branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) Integer hoursBack,
            @RequestParam(required = false) Integer staleMinutes,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), branchId);
        String createdByFilter = createdBy != null && !createdBy.isBlank()
                ? createdBy.trim()
                : null;
        return service.listDrafts(
                businessId, validatedBranch, status, createdByFilter, hoursBack, staleMinutes);
    }

    @PatchMapping("/{id}/lines")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public GroceryDraftResponse patchLines(
            @PathVariable String id,
            @Valid @RequestBody PatchGroceryDraftLinesRequest body,
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
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public GroceryDraftResponse putLine(
            @PathVariable String id,
            @PathVariable String lineId,
            @Valid @RequestBody PutGroceryDraftLineRequest body,
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
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public GroceryDraftResponse deleteLine(
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

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasPermission(null, 'grocery.invoices.create')")
    public ResponseEntity<IssueGroceryDraftResponse> issueDraft(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @jakarta.validation.constraints.NotBlank String idempotencyKey,
            @Valid @RequestBody(required = false) IssueGroceryDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        IssueGroceryDraftRequest safe = body == null
                ? new IssueGroceryDraftRequest(null, null)
                : body;
        IssueGroceryDraftResponse response = service.issueDraft(
                TenantRequestIds.resolveBusinessId(request),
                id,
                safe,
                idempotencyKey,
                principal.userId()
        );
        HttpStatus status = response.createdNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'grocery.drafts.cancel.own') or hasPermission(null, 'grocery.invoices.cancel')")
    public GroceryDraftResponse cancelDraft(
            @PathVariable String id,
            @Valid @RequestBody(required = false) CancelGroceryDraftRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        GroceryDraftResponse existing = service.getDraft(businessId, id, false);
        enforceCancelAccess(principal, existing);
        return service.cancelDraft(
                businessId,
                id,
                body == null ? new CancelGroceryDraftRequest(null) : body,
                principal.userId()
        );
    }

    private void enforceCancelAccess(TenantPrincipal principal, GroceryDraftResponse draft) {
        boolean canCancelAny = requestPermissionService.hasPermission(
                principal.roleId(), "grocery.invoices.cancel");
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
