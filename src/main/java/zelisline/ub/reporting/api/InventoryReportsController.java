package zelisline.ub.reporting.api;

import java.time.LocalDate;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.reporting.api.dto.InventoryExpiryPipelineResponse;
import zelisline.ub.reporting.api.dto.InventoryValuationResponse;
import zelisline.ub.reporting.application.InventoryReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

@RestController
@RequestMapping("/api/v1/reports/inventory")
@RequiredArgsConstructor
public class InventoryReportsController {

    private final InventoryReportsService inventoryReportsService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/valuation")
    @PreAuthorize("hasPermission(null, 'reports.inventory.read')")
    public InventoryValuationResponse valuation(
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveBranchForReport(
                businessId, principal.roleId(), principal.branchId(), branchId);
        return inventoryReportsService.valuation(businessId, effectiveBranch);
    }

    @GetMapping("/expiry-pipeline")
    @PreAuthorize("hasPermission(null, 'reports.inventory.read')")
    public InventoryExpiryPipelineResponse expiry(
            @RequestParam(value = "branchId", required = false) String branchId,
            @RequestParam(value = "asOf", required = false) LocalDate asOf,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveBranchForReport(
                businessId, principal.roleId(), principal.branchId(), branchId);
        return inventoryReportsService.expiryPipeline(businessId, effectiveBranch, asOf);
    }
}
