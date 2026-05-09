package zelisline.ub.inventory.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.ClearBatchRequest;
import zelisline.ub.inventory.api.dto.PatchSupplyBatchRequest;
import zelisline.ub.inventory.api.dto.PostSupplyBatchExpenseRequest;
import zelisline.ub.inventory.api.dto.SupplyBatchDetailResponse;
import zelisline.ub.inventory.api.dto.SupplyBatchExpenseResponse;
import zelisline.ub.inventory.api.dto.SupplyBatchSummaryResponse;
import zelisline.ub.inventory.application.SupplyBatchClearanceService;
import zelisline.ub.inventory.application.SupplyBatchReportService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/inventory/supply-batches")
@RequiredArgsConstructor
public class SupplyBatchController {

    private final SupplyBatchReportService supplyBatchReportService;
    private final SupplyBatchClearanceService supplyBatchClearanceService;

    // ── Core batch endpoints ───────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public List<SupplyBatchSummaryResponse> list(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return supplyBatchReportService.listSupplyBatches(
                TenantRequestIds.resolveBusinessId(request),
                branchId,
                supplierId,
                status
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public SupplyBatchDetailResponse detail(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return supplyBatchReportService.getSupplyBatchDetail(
                TenantRequestIds.resolveBusinessId(request),
                id
        );
    }

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recalculate(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        supplyBatchReportService.recalculateTotals(
                TenantRequestIds.resolveBusinessId(request),
                id
        );
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patch(
            @PathVariable String id,
            @Valid @RequestBody PatchSupplyBatchRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        supplyBatchReportService.patchSupplyBatch(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body
        );
    }

    @PostMapping("/{id}/clear")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public SupplyBatchClearanceService.ClearanceResult clear(
            @PathVariable String id,
            @Valid @RequestBody ClearBatchRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String userId = CurrentTenantUser.auditActorId(request);
        zelisline.ub.inventory.WastageReason reason = zelisline.ub.inventory.WastageReason.fromString(body.reason());
        return supplyBatchClearanceService.clearBatch(
                businessId, id, reason, body.notes(), userId
        );
    }

    // ── Expense endpoints ──────────────────────────────────────────

    @GetMapping("/{id}/expenses")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public List<SupplyBatchExpenseResponse> listExpenses(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        SupplyBatchDetailResponse detail = supplyBatchReportService.getSupplyBatchDetail(
                TenantRequestIds.resolveBusinessId(request), id);
        return detail.expenses();
    }

    @PostMapping("/{id}/expenses")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplyBatchExpenseResponse addExpense(
            @PathVariable String id,
            @Valid @RequestBody PostSupplyBatchExpenseRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String userId = CurrentTenantUser.auditActorId(request);
        return supplyBatchReportService.addExpense(businessId, id, body, userId);
    }

    @DeleteMapping("/{id}/expenses/{expenseId}")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpense(
            @PathVariable String id,
            @PathVariable String expenseId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        supplyBatchReportService.deleteExpense(
                TenantRequestIds.resolveBusinessId(request), id, expenseId
        );
    }
}
