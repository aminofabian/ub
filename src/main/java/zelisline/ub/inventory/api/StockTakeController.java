package zelisline.ub.inventory.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.ApproveStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.PatchStockTakeCountsRequest;
import zelisline.ub.inventory.api.dto.PostStartStockTakeSessionRequest;
import zelisline.ub.inventory.api.dto.RejectStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.StockTakeSessionResponse;
import zelisline.ub.inventory.application.StockTakeService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/stock-take")
@RequiredArgsConstructor
public class StockTakeController {

    private final StockTakeService stockTakeService;

    @PostMapping("/sessions")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeSessionResponse startSession(
            @Valid @RequestBody PostStartStockTakeSessionRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.startSession(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public StockTakeSessionResponse getSession(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return stockTakeService.getSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId
        );
    }

    @PatchMapping("/sessions/{sessionId}/lines")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeSessionResponse applyCounts(
            @PathVariable String sessionId,
            @Valid @RequestBody PatchStockTakeCountsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return stockTakeService.applyCounts(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body
        );
    }

    @PostMapping("/sessions/{sessionId}/close")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeSessionResponse closeSession(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.closeSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                user.userId()
        );
    }

    @PostMapping("/sessions/{sessionId}/adjustment-requests/{requestId}/approve")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveRequest(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestBody(required = false) ApproveStockAdjustmentRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        stockTakeService.approveAdjustmentRequest(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                requestId,
                body,
                user.userId()
        );
    }

    @PostMapping("/sessions/{sessionId}/adjustment-requests/{requestId}/reject")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectRequest(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestBody(required = false) RejectStockAdjustmentRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String notes = body == null ? null : body.notes();
        stockTakeService.rejectAdjustmentRequest(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                requestId,
                notes,
                user.userId()
        );
    }
}
