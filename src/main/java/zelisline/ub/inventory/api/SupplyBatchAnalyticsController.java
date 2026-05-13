package zelisline.ub.inventory.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.analytics.BatchDashboardResponse;
import zelisline.ub.inventory.api.dto.analytics.BatchTableResponse;
import zelisline.ub.inventory.application.SupplyBatchAnalyticsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@RestController
@RequestMapping("/api/v1/inventory/supply-batches/analytics")
@RequiredArgsConstructor
public class SupplyBatchAnalyticsController {

    private final SupplyBatchAnalyticsService analyticsService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public BatchDashboardResponse dashboard(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveBranchForReport(
                businessId, principal.roleId(), principal.branchId(), branchId);
        return analyticsService.getDashboard(businessId, effectiveBranch, from, to);
    }

    @GetMapping("/table")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public BatchTableResponse table(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String quantityMin,
            @RequestParam(required = false) String quantityMax,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "receivedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveBranchForReport(
                businessId, principal.roleId(), principal.branchId(), branchId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return analyticsService.getTable(
                businessId, effectiveBranch, status, search, from, to,
                quantityMin, quantityMax, page, safeSize, sortBy, sortDir
        );
    }
}
