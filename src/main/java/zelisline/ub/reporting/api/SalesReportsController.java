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
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.application.SalesReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Phase 7 Slice 2 — Report #2 (Sales register). MV-backed for past days, OLTP for
 * today; clients see a single contract.
 */
@RestController
@RequestMapping("/api/v1/reports/sales")
@RequiredArgsConstructor
public class SalesReportsController {

    private final SalesReportsService salesReportsService;

    @GetMapping("/register")
    @PreAuthorize("hasPermission(null, 'reports.sales.read')")
    public SalesRegisterResponse salesRegister(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesReportsService.salesRegister(TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }
}
