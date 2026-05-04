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
import zelisline.ub.reporting.api.dto.InventoryExpiryPipelineResponse;
import zelisline.ub.reporting.api.dto.InventoryValuationResponse;
import zelisline.ub.reporting.application.InventoryReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/reports/inventory")
@RequiredArgsConstructor
public class InventoryReportsController {

    private final InventoryReportsService inventoryReportsService;

    @GetMapping("/valuation")
    @PreAuthorize("hasPermission(null, 'reports.inventory.read')")
    public InventoryValuationResponse valuation(
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return inventoryReportsService.valuation(TenantRequestIds.resolveBusinessId(request), branchId);
    }

    @GetMapping("/expiry-pipeline")
    @PreAuthorize("hasPermission(null, 'reports.inventory.read')")
    public InventoryExpiryPipelineResponse expiry(
            @RequestParam(value = "branchId", required = false) String branchId,
            @RequestParam(value = "asOf", required = false) LocalDate asOf,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return inventoryReportsService.expiryPipeline(
                TenantRequestIds.resolveBusinessId(request), branchId, asOf);
    }
}
