package zelisline.ub.inventory.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.BatchSafetyCountsResponse;
import zelisline.ub.inventory.api.dto.BatchSafetyListResponse;
import zelisline.ub.inventory.api.dto.DisposeBatchRequest;
import zelisline.ub.inventory.api.dto.EditBatchDetailsRequest;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.QuarantineBatchRequest;
import zelisline.ub.inventory.api.dto.RecallQuarantineRequest;
import zelisline.ub.inventory.api.dto.RecallReportResponse;
import zelisline.ub.inventory.application.BatchQuarantineService;
import zelisline.ub.inventory.application.BatchRecallService;
import zelisline.ub.inventory.application.BatchSafetyQueryService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Stock safety for any shop that tracks lots and expiry: quarantine a batch, release it, dispose
 * of it, correct its lot or expiry, and trace a lot for a recall. The business always comes from
 * the request. Recall output carries customer contact details, so it needs {@code inventory.write}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/batch-safety")
@RequiredArgsConstructor
public class BatchSafetyController {

    private final BatchSafetyQueryService batchSafetyQueryService;
    private final BatchQuarantineService batchQuarantineService;
    private final BatchRecallService batchRecallService;
    private final zelisline.ub.tenancy.application.BranchResolutionService branchResolutionService;

    /**
     * The one branch a branch-tied role (stock manager, cashier) may see and act on, or null for
     * roles that work across branches.
     */
    private String lockedBranch(zelisline.ub.platform.security.TenantPrincipal user, String businessId) {
        if (!branchResolutionService.isBranchLockedRole(user.roleId())) {
            return null;
        }
        return branchResolutionService.resolveBranchForReport(businessId, user.roleId(), user.branchId(), null);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public BatchSafetyListResponse list(
            @RequestParam(defaultValue = "quarantined") String view,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) Integer days,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        return batchSafetyQueryService.list(businessId, view, locked != null ? locked : branchId, days);
    }

    @GetMapping("/counts")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public BatchSafetyCountsResponse counts(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) Integer days,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        return batchSafetyQueryService.counts(businessId, locked != null ? locked : branchId, days);
    }

    @PostMapping("/batches/{batchId}/quarantine")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public Map<String, String> quarantine(
            @PathVariable String batchId,
            @Valid @RequestBody QuarantineBatchRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        batchQuarantineService.assertBatchInBranch(TenantRequestIds.resolveBusinessId(request), batchId,
                lockedBranch(user, TenantRequestIds.resolveBusinessId(request)));
        InventoryBatch b = batchQuarantineService.quarantine(
                TenantRequestIds.resolveBusinessId(request), batchId, body.reason(), user.userId());
        return Map.of("batchId", b.getId(), "status", b.getStatus());
    }

    @PostMapping("/batches/{batchId}/release")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public Map<String, String> release(
            @PathVariable String batchId,
            @Valid @RequestBody QuarantineBatchRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        batchQuarantineService.assertBatchInBranch(TenantRequestIds.resolveBusinessId(request), batchId,
                lockedBranch(user, TenantRequestIds.resolveBusinessId(request)));
        InventoryBatch b = batchQuarantineService.release(
                TenantRequestIds.resolveBusinessId(request), batchId, body.reason(), user.userId());
        return Map.of("batchId", b.getId(), "status", b.getStatus());
    }

    @PostMapping("/batches/{batchId}/dispose")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public InventoryMutationResponse dispose(
            @PathVariable String batchId,
            @Valid @RequestBody DisposeBatchRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        batchQuarantineService.assertBatchInBranch(TenantRequestIds.resolveBusinessId(request), batchId,
                lockedBranch(user, TenantRequestIds.resolveBusinessId(request)));
        return batchQuarantineService.dispose(
                TenantRequestIds.resolveBusinessId(request), batchId, body, user.userId());
    }

    @PatchMapping("/batches/{batchId}")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public Map<String, String> editDetails(
            @PathVariable String batchId,
            @Valid @RequestBody EditBatchDetailsRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        batchQuarantineService.assertBatchInBranch(TenantRequestIds.resolveBusinessId(request), batchId,
                lockedBranch(user, TenantRequestIds.resolveBusinessId(request)));
        InventoryBatch b = batchQuarantineService.editDetails(
                TenantRequestIds.resolveBusinessId(request), batchId, body, user.userId());
        return Map.of("batchId", b.getId(), "status", b.getStatus());
    }

    @GetMapping("/recall")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public RecallReportResponse recall(
            @RequestParam String lot,
            @RequestParam(required = false) String itemId,
            HttpServletRequest request
    ) {
        requireAllBranches(CurrentTenantUser.requireHuman(request));
        return batchSafetyQueryService.recall(TenantRequestIds.resolveBusinessId(request), lot, itemId);
    }

    @PostMapping("/recall/quarantine")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public Map<String, List<String>> recallQuarantine(
            @Valid @RequestBody RecallQuarantineRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        requireAllBranches(user);
        List<String> ids = batchRecallService.quarantineLot(
                TenantRequestIds.resolveBusinessId(request), body, user.userId());
        return Map.of("quarantinedBatchIds", ids);
    }

    /** A recall reaches every branch and lists who bought the lot, so a branch-tied role cannot run one. */
    private void requireAllBranches(zelisline.ub.platform.security.TenantPrincipal user) {
        if (branchResolutionService.isBranchLockedRole(user.roleId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "A recall covers every branch. Ask the owner, a manager or the pharmacist to run it.");
        }
    }
}
