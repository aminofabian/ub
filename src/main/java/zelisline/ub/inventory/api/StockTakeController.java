package zelisline.ub.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.inventory.api.dto.ApproveStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.CreateItemWithStocktakeLineRequest;
import zelisline.ub.inventory.api.dto.PatchStockTakeCountsRequest;
import zelisline.ub.inventory.api.dto.PostStartStockTakeSessionRequest;
import zelisline.ub.inventory.api.dto.ReconciliationResponse;
import zelisline.ub.inventory.api.dto.RejectStockAdjustmentRequest;
import zelisline.ub.inventory.api.dto.StockTakeMyStatsResponse;
import zelisline.ub.inventory.api.dto.StockTakeSessionResponse;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.application.InventoryRoleAccessService;
import zelisline.ub.inventory.application.StockTakeService;
import zelisline.ub.inventory.application.StockTakeStatsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@Validated
@RestController
@RequestMapping("/api/v1/inventory/stock-take")
@RequiredArgsConstructor
public class StockTakeController {

    private static final String PERMISSION_STOCKTAKE_APPROVE =
        "stocktake.approve";

    private final StockTakeService stockTakeService;
    private final StockTakeStatsService stockTakeStatsService;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService permissionService;
    private final InventoryRoleAccessService inventoryRoleAccessService;

    // ── Counter personal stats ────────────────────────────────────────

    @GetMapping("/my-stats")
    @PreAuthorize("hasPermission(null, 'stocktake.read') or hasPermission(null, 'stocktake.run')")
    public StockTakeMyStatsResponse myStats(
        @RequestParam(required = false) String month,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        YearMonth yearMonth = null;
        if (month != null && !month.isBlank()) {
            try {
                yearMonth = YearMonth.parse(month.trim());
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "month must be YYYY-MM"
                );
            }
        }
        return stockTakeStatsService.myMonthStats(
            TenantRequestIds.resolveBusinessId(request),
            principal.userId(),
            yearMonth
        );
    }

    // ── Sessions ──────────────────────────────────────────────────────

    @PostMapping("/sessions")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeSessionResponse startSession(
        @Valid @RequestBody PostStartStockTakeSessionRequest body,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String validatedBranch =
            branchResolutionService.requireBranchForLockedRole(
                principal.roleId(),
                principal.branchId(),
                body.branchId()
            );
        PostStartStockTakeSessionRequest effective =
            new PostStartStockTakeSessionRequest(
                validatedBranch,
                body.sessionType(),
                body.sessionDate(),
                body.notes(),
                body.itemIds()
            );
        return maskIfNeeded(
            stockTakeService.startSession(
                TenantRequestIds.resolveBusinessId(request),
                effective,
                principal.userId()
            ),
            principal
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
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String filterBranch = coerceOptionalBranchFilter(principal, branchId);
        return maskListIfNeeded(
            stockTakeService.listSessions(
                TenantRequestIds.resolveBusinessId(request),
                filterBranch,
                status,
                from,
                to
            ),
            principal
        );
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public StockTakeSessionResponse getSession(
        @PathVariable String sessionId,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return maskIfNeeded(
            stockTakeService.getSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId
            ),
            principal
        );
    }

    @GetMapping("/sessions/active")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public ActiveSessionResponse getActiveSession(
        @RequestParam @NotBlank String branchId,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) String sessionType,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranchId =
            branchResolutionService.requireBranchForLockedRole(
                principal.roleId(),
                principal.branchId(),
                branchId
            );
        LocalDate today = date != null ? date : LocalDate.now();

        var active = stockTakeService.getActiveSession(
            businessId,
            effectiveBranchId,
            today,
            sessionType
        );

        StockTakeSessionResponse stale = null;
        if (active.isEmpty()) {
            stale = stockTakeService
                .getStaleSession(businessId, effectiveBranchId, today)
                .orElse(null);
        }

        return new ActiveSessionResponse(
            active.map(s -> maskIfNeeded(s, principal)).orElse(null),
            stale != null,
            stale != null ? stale.sessionDate() : null,
            stale != null ? stale.id() : null
        );
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasPermission(null, 'stocktake.delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(
        @PathVariable String sessionId,
        HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        stockTakeService.deleteSession(
            TenantRequestIds.resolveBusinessId(request),
            sessionId,
            user.userId()
        );
    }

    @PostMapping("/sessions/{sessionId}/resume")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    public StockTakeSessionResponse resumeSession(
        @PathVariable String sessionId,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return maskIfNeeded(
            stockTakeService.resumeSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId
            ),
            principal
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
        return maskIfNeeded(
            stockTakeService.closeSession(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                user.userId(),
                force
            ),
            user
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
        return maskIfNeeded(
            stockTakeService.applyCounts(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body,
                user.userId()
            ),
            user
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
        return maskIfNeeded(
            stockTakeService.applySingleCount(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId,
                body.countedQty(),
                body.aisle(),
                body.batches(),
                user.userId()
            ),
            user
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
        return maskIfNeeded(
            stockTakeService.addAdHocLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body.itemId(),
                body.aisle(),
                user.userId()
            ),
            user
        );
    }

    @PostMapping("/sessions/{sessionId}/lines/with-product")
    @PreAuthorize("hasPermission(null, 'stocktake.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeSessionResponse createItemAndAddLine(
        @PathVariable String sessionId,
        @Valid @RequestBody CreateItemWithStocktakeLineRequest body,
        HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return maskIfNeeded(
            stockTakeService.createItemAndAddLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                body,
                user.userId()
            ),
            user
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
        return maskIfNeeded(
            stockTakeService.confirmLine(
                TenantRequestIds.resolveBusinessId(request),
                sessionId,
                lineId,
                adminQuantity,
                user.userId()
            ),
            user
        );
    }

    // ── Reconciliation ───────────────────────────────────────────────

    @GetMapping("/sessions/reconciliation")
    @PreAuthorize("hasPermission(null, 'stocktake.read')")
    public ReconciliationResponse getReconciliation(
        @RequestParam(required = false) String morningSessionId,
        @RequestParam(required = false) String eveningSessionId,
        @RequestParam(required = false) String branchId,
        @RequestParam(required = false) @DateTimeFormat(
            iso = DateTimeFormat.ISO.DATE
        ) LocalDate date,
        HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        // Auto-detect: find morning & evening sessions by branch + date
        if (
            (morningSessionId == null || morningSessionId.isBlank()) &&
            (eveningSessionId == null || eveningSessionId.isBlank()) &&
            branchId != null &&
            !branchId.isBlank() &&
            date != null
        ) {
            String effectiveBranchId =
                branchResolutionService.requireBranchForLockedRole(
                    principal.roleId(),
                    principal.branchId(),
                    branchId
                );
            return stockTakeService.getReconciliationByBranchAndDate(
                businessId,
                effectiveBranchId,
                date
            );
        }

        if (
            morningSessionId == null ||
            morningSessionId.isBlank() ||
            eveningSessionId == null ||
            eveningSessionId.isBlank()
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Provide both session IDs, or branchId + date for auto-detection."
            );
        }

        return stockTakeService.getReconciliation(
            businessId,
            morningSessionId,
            eveningSessionId
        );
    }

    // ── Adjustment requests ───────────────────────────────────────────

    @PostMapping(
        "/sessions/{sessionId}/adjustment-requests/{requestId}/approve"
    )
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
    ) {}

    public record PatchSingleLineRequest(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin(
            value = "0",
            inclusive = true
        ) BigDecimal countedQty,
        @jakarta.validation.constraints.Size(max = 255) String aisle,
        java.util.List<PatchStockTakeCountsRequest.BatchCounted> batches
    ) {}

    public record AddAdHocLineRequest(
        @NotBlank String itemId,
        @jakarta.validation.constraints.Size(max = 255) String aisle
    ) {}

    public record ConfirmLineRequest(
        @jakarta.validation.constraints.DecimalMin(
            value = "0",
            inclusive = true
        ) BigDecimal adminQuantity
    ) {}

    /**
     * Optional branch filter for session lists. Branch-locked roles are always scoped to their
     * assigned branch (implicit when the query omits {@code branchId}).
     */
    private String coerceOptionalBranchFilter(
        TenantPrincipal principal,
        String branchId
    ) {
        if (!branchResolutionService.isBranchLockedRole(principal.roleId())) {
            return branchId == null || branchId.isBlank()
                ? null
                : branchId.trim();
        }
        String assigned =
            principal.branchId() != null ? principal.branchId().trim() : "";
        if (assigned.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Your account is not assigned to a branch. Contact your administrator."
            );
        }
        if (branchId == null || branchId.isBlank()) {
            return assigned;
        }
        return branchResolutionService.requireBranchForLockedRole(
            principal.roleId(),
            principal.branchId(),
            branchId
        );
    }

    private boolean canApprove(TenantPrincipal principal) {
        return permissionService.hasPermission(
            principal.roleId(),
            PERMISSION_STOCKTAKE_APPROVE
        );
    }

    private StockTakeSessionResponse maskIfNeeded(
        StockTakeSessionResponse response,
        TenantPrincipal principal
    ) {
        if (canSeeSystemStock(principal)) {
            return response;
        }
        return stockTakeService.maskSystemQty(response);
    }

    private List<StockTakeSessionResponse> maskListIfNeeded(
        List<StockTakeSessionResponse> responses,
        TenantPrincipal principal
    ) {
        if (canSeeSystemStock(principal)) {
            return responses;
        }
        return responses
            .stream()
            .map(stockTakeService::maskSystemQty)
            .toList();
    }

    private boolean canSeeSystemStock(TenantPrincipal principal) {
        return inventoryRoleAccessService.canSeeSystemStockDuringCount(
            principal.businessId(),
            principal.roleId(),
            canApprove(principal)
        );
    }
}
