package zelisline.ub.inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
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
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.ApproveStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.PatchStockTakeCountsRequest;
import zelisline.ub.inventory.api.dto.PostStartStockTakeSessionRequest;
import zelisline.ub.inventory.api.dto.ReconciliationResponse;
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

    // ── Sessions ──────────────────────────────────────────────────────

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

    @GetMapping("/sessions")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public List<StockTakeSessionResponse> listSessions(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return stockTakeService.listSessions(
                TenantRequestIds.resolveBusinessId(request),
                branchId,
                status,
                from,
                to
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

    @GetMapping("/sessions/active")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public ActiveSessionResponse getActiveSession(
            @RequestParam @NotBlank String branchId,
            @RequestParam(required = false) LocalDate date,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        LocalDate today = date != null ? date : LocalDate.now();

        var active = stockTakeService.getActiveSession(businessId, branchId, today);

        StockTakeSessionResponse stale = null;
        if (active.isEmpty()) {
            stale = stockTakeService.getStaleSession(businessId, branchId, today).orElse(null);
        }

        return new ActiveSessionResponse(
                active.orElse(null),
                stale != null,
                stale != null ? stale.sessionDate() : null,
                stale != null ? stale.id() : null
        );
    }

    @PostMapping("/sessions/{sessionId}/close")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeSessionResponse closeSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.closeSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                user.userId(),
                force
        );
    }

    // ── Lines — counting ──────────────────────────────────────────────

    @PatchMapping("/sessions/{sessionId}/lines")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeSessionResponse applyCounts(
            @PathVariable String sessionId,
            @Valid @RequestBody PatchStockTakeCountsRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.applyCounts(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body,
                user.userId()
        );
    }

    @PatchMapping("/sessions/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeSessionResponse applySingleCount(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            @Valid @RequestBody PatchSingleLineRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.applySingleCount(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId,
                body.countedQty(),
                body.aisle(),
                user.userId()
        );
    }

    @PostMapping("/sessions/{sessionId}/lines")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeSessionResponse addAdHocLine(
            @PathVariable String sessionId,
            @Valid @RequestBody AddAdHocLineRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return stockTakeService.addAdHocLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body.itemId(),
                body.aisle(),
                user.userId()
        );
    }

    // ── Lines — admin confirmation ────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/lines/{lineId}/confirm")
    @PreAuthorize("hasPermission(null, 'stocktake.approve')")
    public StockTakeSessionResponse confirmLine(
            @PathVariable String sessionId,
            @PathVariable String lineId,
            @RequestBody(required = false) ConfirmLineRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        BigDecimal adminQuantity = body != null ? body.adminQuantity() : null;
        return stockTakeService.confirmLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId,
                adminQuantity,
                user.userId()
        );
    }

    // ── Reconciliation ───────────────────────────────────────────────

    @GetMapping("/sessions/reconciliation")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public ReconciliationResponse getReconciliation(
            @RequestParam(required = false) String morningSessionId,
            @RequestParam(required = false) String eveningSessionId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        // Auto-detect: find morning & evening sessions by branch + date
        if ((morningSessionId == null || morningSessionId.isBlank())
                && (eveningSessionId == null || eveningSessionId.isBlank())
                && branchId != null && !branchId.isBlank()
                && date != null) {
            return stockTakeService.getReconciliationByBranchAndDate(
                    businessId, branchId, date);
        }

        if (morningSessionId == null || morningSessionId.isBlank()
                || eveningSessionId == null || eveningSessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide both session IDs, or branchId + date for auto-detection.");
        }

        return stockTakeService.getReconciliation(
                businessId, morningSessionId, eveningSessionId);
    }

    // ── Adjustment requests ───────────────────────────────────────────

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

    // ── Inline DTOs ───────────────────────────────────────────────────

    public record ActiveSessionResponse(
            StockTakeSessionResponse session,
            boolean hasStaleSession,
            LocalDate staleSessionDate,
            String staleSessionId
    ) {
    }

    public record PatchSingleLineRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = true)
            BigDecimal countedQty,
            @jakarta.validation.constraints.Size(max = 255)
            String aisle
    ) {
    }

    public record AddAdHocLineRequest(
            @NotBlank String itemId,
            @jakarta.validation.constraints.Size(max = 255) String aisle
    ) {
    }

    public record ConfirmLineRequest(
            @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = true)
            BigDecimal adminQuantity
    ) {
    }
}
