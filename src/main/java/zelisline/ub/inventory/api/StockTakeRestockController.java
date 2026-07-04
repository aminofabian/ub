package zelisline.ub.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.GenerateRestockOrderResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.RestockOrderSummary;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockItemResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockReviewResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockSupplierOptionsResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.GenerateRestockOrderRequest;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.PatchStockTakeRestockRequest;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.PostDailyAuditRestockRequest;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.RejectStockTakeRestockRequest;
import zelisline.ub.inventory.application.StockTakeRestockService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/stock-take/restock-items")
@RequiredArgsConstructor
public class StockTakeRestockController {

    private final StockTakeRestockService stockTakeRestockService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/daily-audit/sessions/{sessionId}/lines/{lineId}/supplier-options")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeRestockSupplierOptionsResponse getSupplierOptions(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            HttpServletRequest request
    ) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.getSupplierOptions(businessId, sessionId, lineId);
    }

    @PostMapping("/daily-audit/sessions/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeRestockItemResponse upsertSuggestion(
            @PathVariable String sessionId,
            @Valid @RequestBody PostDailyAuditRestockRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.upsertSuggestion(
                businessId, sessionId, principal.userId(), body);
    }

    @GetMapping("/review")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeRestockReviewResponse getReview(
            @RequestParam @NotBlank String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate auditDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch =
                branchResolutionService.requireBranchForLockedRole(
                        principal.roleId(), principal.branchId(), branchId);
        return stockTakeRestockService.getReview(
                businessId, effectiveBranch, auditDate, status, supplierId);
    }

    @PatchMapping("/{restockItemId}")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeRestockItemResponse patchItem(
            @PathVariable String restockItemId,
            @Valid @RequestBody PatchStockTakeRestockRequest body,
            HttpServletRequest request
    ) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.patchItem(businessId, restockItemId, body);
    }

    @PostMapping("/{restockItemId}/approve")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeRestockItemResponse approveItem(
            @PathVariable String restockItemId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.approveItem(businessId, restockItemId, principal.userId());
    }

    @PostMapping("/{restockItemId}/reject")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeRestockItemResponse rejectItem(
            @PathVariable String restockItemId,
            @Valid @RequestBody RejectStockTakeRestockRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.rejectItem(
                businessId, restockItemId, principal.userId(), body.reason());
    }

    @DeleteMapping("/{restockItemId}")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String restockItemId, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        stockTakeRestockService.deleteItem(businessId, restockItemId);
    }

    @PostMapping("/generate-order")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public GenerateRestockOrderResponse generateOrder(
            @RequestParam @NotBlank String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate auditDate,
            @Valid @RequestBody(required = false) GenerateRestockOrderRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch =
                branchResolutionService.requireBranchForLockedRole(
                        principal.roleId(), principal.branchId(), branchId);
        return stockTakeRestockService.generateOrder(
                businessId, effectiveBranch, auditDate, principal.userId(), body);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public List<RestockOrderSummary> listOrders(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return stockTakeRestockService.listOrders(businessId, from, to, supplierId, status);
    }

    @PostMapping("/orders/{orderId}/mark-ordered")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markOrderOrdered(@PathVariable String orderId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        stockTakeRestockService.markOrderOrdered(businessId, orderId, principal.userId());
    }

    @PostMapping("/orders/{orderId}/mark-received")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markOrderReceived(@PathVariable String orderId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        stockTakeRestockService.markOrderReceived(businessId, orderId, principal.userId());
    }
}
