package zelisline.ub.inventory.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.api.dto.BranchValuationLine;
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.InventoryValuationResponse;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.api.dto.StockTransferCreatedResponse;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.inventory.application.InventoryTransferService;
import zelisline.ub.inventory.application.InventoryValuationService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryLedgerService inventoryLedgerService;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final InventoryTransferService inventoryTransferService;
    private final InventoryValuationService inventoryValuationService;

    @GetMapping("/allocation-preview")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public List<BatchAllocationLine> allocationPreview(
            @RequestParam @NotBlank String itemId,
            @RequestParam @NotBlank String branchId,
            @RequestParam @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
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
        var user = CurrentTenantUser.require(request);
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
        var user = CurrentTenantUser.require(request);
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
        var user = CurrentTenantUser.require(request);
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
        var user = CurrentTenantUser.require(request);
        return inventoryLedgerService.recordStandaloneWastage(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasPermission(null, 'inventory.transfer')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTransferCreatedResponse createTransfer(
            @Valid @RequestBody PostStockTransferRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.require(request);
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
        var user = CurrentTenantUser.require(request);
        inventoryTransferService.completeTransfer(
                TenantRequestIds.resolveBusinessId(request),
                transferId,
                user.userId()
        );
    }

    @GetMapping("/valuation")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public InventoryValuationResponse valuation(
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return inventoryValuationService.valuation(
                TenantRequestIds.resolveBusinessId(request),
                branchId
        );
    }
}
