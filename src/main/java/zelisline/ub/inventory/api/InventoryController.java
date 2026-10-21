package zelisline.ub.inventory.api;

import java.math.BigDecimal;
import java.util.List;

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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.api.dto.BranchValuationLine;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.InventoryValuationResponse;
import zelisline.ub.inventory.api.dto.ItemStockThresholdsResponse;
import zelisline.ub.inventory.api.dto.PatchItemStockThresholdsRequest;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.api.dto.PostSupplierReturnRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.api.dto.StockTransferCreatedResponse;
import zelisline.ub.inventory.api.dto.StockTransferSummaryResponse;
import zelisline.ub.inventory.api.dto.EquityClearanceCorrectionResponse;
import zelisline.ub.inventory.api.dto.StockDriftResponse;
import zelisline.ub.inventory.application.EquityFundedClearanceCorrectionService;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.InventoryDriftService;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.inventory.application.InventoryTransferService;
import zelisline.ub.inventory.application.InventoryValuationService;
import zelisline.ub.inventory.application.ItemStockThresholdsService;
import zelisline.ub.inventory.application.InventoryRoleAccessService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryLedgerService inventoryLedgerService;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final InventoryTransferService inventoryTransferService;
    private final InventoryValuationService inventoryValuationService;
    private final InventoryDriftService inventoryDriftService;
    private final EquityFundedClearanceCorrectionService equityFundedClearanceCorrectionService;
    private final ItemStockThresholdsService itemStockThresholdsService;
    private final InventoryRoleAccessService inventoryRoleAccessService;
    private final RequestPermissionService requestPermissionService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/allocation-preview")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public List<BatchAllocationLine> allocationPreview(
            @RequestParam @NotBlank String itemId,
            @RequestParam @NotBlank String branchId,
            @RequestParam @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return inventoryBatchPickerService.previewAllocation(
                TenantRequestIds.resolveBusinessId(request),
                itemId,
                branchId,
                quantity
        );
    }

    @PostMapping("/opening-balance")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryMutationResponse openingBalance(
            @Valid @RequestBody PostOpeningBalanceRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryLedgerService.recordOpeningBalance(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/stock-increase")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryMutationResponse stockIncrease(
            @Valid @RequestBody PostStockIncreaseRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryLedgerService.recordStockIncrease(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/batch-decrease")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryMutationResponse batchDecrease(
            @Valid @RequestBody PostBatchDecreaseRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryLedgerService.recordBatchDecrease(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/wastage")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryMutationResponse wastage(
            @Valid @RequestBody PostStandaloneWastageRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryLedgerService.recordStandaloneWastage(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/return-to-supplier")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryMutationResponse returnToSupplier(
            @Valid @RequestBody PostSupplierReturnRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryLedgerService.recordSupplierReturn(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    public List<StockTransferSummaryResponse> listTransfers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        return inventoryTransferService.listTransfers(
                TenantRequestIds.resolveBusinessId(request),
                status,
                branchId
        );
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTransferCreatedResponse createTransfer(
            @Valid @RequestBody PostStockTransferRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return inventoryTransferService.createDraft(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/transfers/{transferId}/complete")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeTransfer(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        inventoryTransferService.completeTransfer(
                TenantRequestIds.resolveBusinessId(request),
                transferId,
                user.userId()
        );
    }

    @PostMapping("/transfers/{transferId}/send")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendTransfer(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        inventoryTransferService.sendTransfer(
                TenantRequestIds.resolveBusinessId(request),
                transferId,
                user.userId()
        );
    }

    @PostMapping("/transfers/{transferId}/receive")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiveTransfer(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        inventoryTransferService.receiveTransfer(
                TenantRequestIds.resolveBusinessId(request),
                transferId,
                user.userId(),
                user.branchId()
        );
    }

    @PostMapping("/transfers/{transferId}/cancel")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTransfer(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        inventoryTransferService.cancelTransfer(
                TenantRequestIds.resolveBusinessId(request),
                transferId,
                user.userId(),
                user.branchId()
        );
    }

    /**
     * Preview correcting journals for equity-funded batch clearances that wrongly
     * hit shrinkage OpEx. Does not write.
     */
    @GetMapping("/repairs/equity-clearance-correction")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public EquityClearanceCorrectionResponse previewEquityClearanceCorrection(
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return toCorrectionResponse(equityFundedClearanceCorrectionService.preview(
                TenantRequestIds.resolveBusinessId(request)));
    }

    /**
     * Post correcting journals ({@code Dr Opening equity / Cr Shrinkage}) for
     * historical equity-funded clearances. Idempotent.
     */
    @PostMapping("/repairs/equity-clearance-correction")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public EquityClearanceCorrectionResponse applyEquityClearanceCorrection(
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return toCorrectionResponse(equityFundedClearanceCorrectionService.apply(
                TenantRequestIds.resolveBusinessId(request)));
    }

    private static EquityClearanceCorrectionResponse toCorrectionResponse(
            EquityFundedClearanceCorrectionService.CorrectionSummary summary
    ) {
        return new EquityClearanceCorrectionResponse(
                summary.dryRun(),
                summary.candidates(),
                summary.corrected(),
                summary.skipped(),
                summary.totalAmount(),
                summary.lines().stream()
                        .map(l -> new EquityClearanceCorrectionResponse.Line(
                                l.originalJournalEntryId(),
                                l.correctionJournalEntryId(),
                                l.branchId(),
                                l.entryDate(),
                                l.supplyBatchId(),
                                l.batchNumber(),
                                l.batchSourceType(),
                                l.amount()))
                        .toList());
    }

    /**
     * Read-only preview of the oversell-deficit drift: item/branches whose branch on-hand still
     * excludes historical batch-less sale outflows, with the on-hand the backfill would produce.
     */
    @GetMapping("/stock-drift")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public StockDriftResponse stockDrift(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false, defaultValue = "200") int limit,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranchId = branchId;
        if (branchResolutionService.isBranchLockedRole(principal.roleId())) {
            effectiveBranchId = branchResolutionService.resolveBranchForReport(
                    businessId, principal.roleId(), principal.branchId(), branchId);
        }
        return inventoryDriftService.drift(businessId, effectiveBranchId, limit);
    }

    @GetMapping("/valuation")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public InventoryValuationResponse valuation(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranchId = branchId;
        if (branchResolutionService.isBranchLockedRole(principal.roleId())) {
            effectiveBranchId = branchResolutionService.resolveBranchForReport(
                    businessId, principal.roleId(), principal.branchId(), branchId);
        }
        return inventoryValuationService.valuation(businessId, effectiveBranchId, itemTypeId);
    }

    /**
     * Set catalog min / reorder levels (grocery Edit stock). Narrower than full
     * catalog write — gated by {@code inventory.stock_thresholds.write}.
     */
    @PatchMapping("/items/{itemId}/stock-thresholds")
    @PreAuthorize("hasPermission(null, 'inventory.stock_thresholds.write')")
    public ItemStockThresholdsResponse patchStockThresholds(
            @PathVariable String itemId,
            @Valid @RequestBody PatchItemStockThresholdsRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        boolean fullWrite = requestPermissionService.hasPermission(
                        principal.roleId(), "inventory.write")
                || requestPermissionService.hasPermission(
                        principal.roleId(), "catalog.items.write");
        boolean canWritePar = fullWrite
                || inventoryRoleAccessService.grantsDelegatedParLevelWrite(
                        businessId, principal.roleId());
        return itemStockThresholdsService.patch(businessId, itemId, body, canWritePar);
    }
}
