package zelisline.ub.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.ApplyBatchRequest;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.BatchResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountBatchLineRequest;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountCycleHomeResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountCycleResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.StartCountCycleRequest;
import zelisline.ub.inventory.application.InventoryRoleAccessService;
import zelisline.ub.inventory.application.StockCountCycleService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/stock-take/count-cycles")
@RequiredArgsConstructor
public class StockCountCycleController {

    private static final String PERMISSION_STOCKTAKE_APPROVE = "stocktake.approve";

    private final StockCountCycleService cycleService;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService permissionService;
    private final InventoryRoleAccessService inventoryRoleAccessService;

    @GetMapping("/home")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public CountCycleHomeResponse home(
            @RequestParam @NotBlank String branchId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.home(businessId, resolveBranch(principal, branchId));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.CREATED)
    public CountCycleResponse startCycle(
            @Valid @RequestBody StartCountCycleRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.startCycle(
                businessId,
                resolveBranch(principal, body.branchId()),
                body.batchSize(),
                principal.userId()
        );
    }

    @PostMapping("/{cycleId}/batches")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public BatchResponse startOrResumeBatch(
            @PathVariable String cycleId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.startOrResumeBatch(
                businessId, cycleId, principal.userId(), canSeeSystemStock(businessId, principal));
    }

    @PostMapping("/{cycleId}/cancel")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public CountCycleResponse cancelCycle(
            @PathVariable String cycleId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.cancelCycle(businessId, cycleId, principal.userId());
    }

    @GetMapping("/batches/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public BatchResponse getBatch(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.getBatch(businessId, sessionId, canSeeSystemStock(businessId, principal));
    }

    @PatchMapping("/batches/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public BatchResponse countLine(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            @Valid @RequestBody CountBatchLineRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.countLine(
                businessId,
                sessionId,
                lineId,
                body.countedQty(),
                principal.userId(),
                canSeeSystemStock(businessId, principal)
        );
    }

    @PostMapping("/batches/{sessionId}/finish")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public CountCycleResponse finishBatch(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.finishBatch(businessId, sessionId, principal.userId());
    }

    @GetMapping("/batches/{sessionId}/review")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public BatchResponse reviewBatch(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.getBatch(businessId, sessionId, true);
    }

    @PostMapping("/batches/{sessionId}/apply")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public BatchResponse applyBatch(
            @PathVariable String sessionId,
            @RequestBody(required = false) ApplyBatchRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return cycleService.applyBatch(
                businessId,
                sessionId,
                body != null ? body.lineIds() : null,
                principal.userId()
        );
    }

    private boolean canSeeSystemStock(String businessId, TenantPrincipal principal) {
        boolean canApprove = permissionService.hasPermission(
                principal.roleId(),
                PERMISSION_STOCKTAKE_APPROVE
        );
        return inventoryRoleAccessService.canSeeSystemStockDuringCount(
                businessId,
                principal.roleId(),
                canApprove
        );
    }

    private String resolveBranch(TenantPrincipal principal, String branchId) {
        return branchResolutionService.requireBranchForLockedRole(
                principal.roleId(),
                principal.branchId(),
                branchId
        );
    }
}
