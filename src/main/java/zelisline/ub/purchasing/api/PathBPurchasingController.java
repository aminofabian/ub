package zelisline.ub.purchasing.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.purchasing.api.dto.AddPathBLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathBSessionRequest;
import zelisline.ub.purchasing.api.dto.PathBLineResponse;
import zelisline.ub.purchasing.api.dto.PathBSessionDetailResponse;
import zelisline.ub.purchasing.api.dto.PathBSessionListRow;
import zelisline.ub.purchasing.api.dto.PostPathBRequest;
import zelisline.ub.purchasing.api.dto.PostPathBResponse;
import zelisline.ub.purchasing.application.PathBPurchaseService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing/path-b/sessions")
@RequiredArgsConstructor
public class PathBPurchasingController {

    private final PathBPurchaseService pathBPurchaseService;
    private final BranchResolutionService branchResolutionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PathBSessionDetailResponse create(
            @Valid @RequestBody CreatePathBSessionRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String validatedBranch = branchResolutionService.requireBranchForLockedRole(
                principal.roleId(), principal.branchId(), body.branchId());
        CreatePathBSessionRequest safe = new CreatePathBSessionRequest(
                body.supplierId(), validatedBranch, body.receivedAt(), body.notes());
        return pathBPurchaseService.createSession(TenantRequestIds.resolveBusinessId(request), safe);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read')")
    public List<PathBSessionListRow> list(
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathBPurchaseService.listSessions(
                TenantRequestIds.resolveBusinessId(request), supplierId, status);
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.read')")
    public PathBSessionDetailResponse get(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathBPurchaseService.getSession(TenantRequestIds.resolveBusinessId(request), sessionId);
    }

    @PostMapping("/{sessionId}/lines")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PathBLineResponse addLine(
            @PathVariable String sessionId,
            @Valid @RequestBody AddPathBLineRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathBPurchaseService.addLine(TenantRequestIds.resolveBusinessId(request), sessionId, body);
    }

    @PatchMapping("/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    public PathBLineResponse patchLine(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            @Valid @RequestBody AddPathBLineRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathBPurchaseService.patchLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId,
                body
        );
    }

    @DeleteMapping("/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLine(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        pathBPurchaseService.deleteLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId
        );
    }

    @PostMapping("/{sessionId}/post")
    @PreAuthorize("hasPermission(null, 'purchasing.path_b.write')")
    public PostPathBResponse post(
            @PathVariable String sessionId,
            @Valid @RequestBody PostPathBRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return pathBPurchaseService.postSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body,
                idempotencyKey
        );
    }
}
