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
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/inventory/supply-batches/analytics")
@RequiredArgsConstructor
public class SupplyBatchAnalyticsController {

    private final SupplyBatchAnalyticsService analyticsService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public BatchDashboardResponse dashboard(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return analyticsService.getDashboard(businessId, branchId, from, to);
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
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return analyticsService.getTable(
                businessId, branchId, status, search, from, to,
                quantityMin, quantityMax, page, safeSize, sortBy, sortDir
        );
    }
}
