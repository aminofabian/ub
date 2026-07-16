package zelisline.ub.inventory.api;

import java.math.BigDecimal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.api.dto.AdjustItemCostRequest;
import zelisline.ub.inventory.api.dto.CostIssueRowResponse;
import zelisline.ub.inventory.api.dto.CostIssuesResponse;
import zelisline.ub.inventory.application.CostAuditService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * Cost-audit surface: list items with abnormal cost (missing/zero, at-or-above sell price,
 * thin margin, or exaggerated/high margin) and correct them. Reading requires
 * {@code pricing.read}; correcting requires {@code pricing.cost_price.set}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/cost-issues")
@RequiredArgsConstructor
public class CostAuditController {

    private final CostAuditService costAuditService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'pricing.read')")
    public CostIssuesResponse list(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) BigDecimal thinMarginPct,
            @RequestParam(required = false) BigDecimal highMarginPct,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranchId = branchId;
        if (branchResolutionService.isBranchLockedRole(principal.roleId())) {
            effectiveBranchId = branchResolutionService.resolveBranchForReport(
                    businessId, principal.roleId(), principal.branchId(), branchId);
        }
        return costAuditService.listCostIssues(
                businessId, effectiveBranchId, thinMarginPct, highMarginPct);
    }

    @PostMapping("/{itemId}/adjust")
    @PreAuthorize("hasPermission(null, 'pricing.cost_price.set')")
    public CostIssueRowResponse adjust(
            @PathVariable("itemId") String itemId,
            @Valid @RequestBody AdjustItemCostRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return costAuditService.adjustCost(businessId, itemId, body, principal.userId());
    }
}
