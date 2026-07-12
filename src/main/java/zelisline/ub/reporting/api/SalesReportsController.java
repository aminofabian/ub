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
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.application.SalesReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * Phase 7 Slice 2 — Report #2 (Sales register). MV-backed for past days, OLTP for
 * today; clients see a single contract.
 *
 * <p>Phase 9 Slice 2: Branch scoping with {@code reports.branch.all} permission.
 * Users without the HQ permission are scoped to a single branch (session or explicit).
 * Users with the permission may pass no branch for a cross-branch rollup.</p>
 */
@RestController
@RequestMapping("/api/v1/reports/sales")
@RequiredArgsConstructor
public class SalesReportsController {

    private final SalesReportsService salesReportsService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/register")
    @PreAuthorize("hasPermission(null, 'reports.sales.read')")
    public SalesRegisterResponse salesRegister(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            @RequestParam(value = "itemTypeId", required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String effectiveBranch = branchResolutionService.resolveBranchForReport(
                businessId, principal.roleId(), principal.branchId(), branchId);
        return salesReportsService.salesRegister(businessId, from, to, effectiveBranch, itemTypeId);
    }
}
