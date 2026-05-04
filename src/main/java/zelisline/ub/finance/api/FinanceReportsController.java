package zelisline.ub.finance.api;

import java.time.LocalDate;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.BalanceSheetResponse;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.application.FinanceReportsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Phase 7 Slice 0 — Phase 6 close-out gate (PHASE_7_PLAN.md §Slice 0).
 *
 * <p>Three journal-backed read endpoints unblock Phase 7 Slice 5 (dashboard SLO)
 * by giving the owner pulse, simple period P&amp;L, and simple as-of balance sheet
 * surfaces an authoritative source. Phase 7 Slice 2 will accelerate the same numbers
 * with {@code mv_sales_daily} without changing the API contract.</p>
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceReportsController {

    private final FinanceReportsService financeReportsService;

    @GetMapping("/pulse")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public FinancePulseResponse pulse(
            @RequestParam(value = "date", required = false) LocalDate date,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.pulse(TenantRequestIds.resolveBusinessId(request), date, branchId);
    }

    @GetMapping("/pl")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public ProfitAndLossResponse profitAndLoss(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.profitAndLoss(TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    public BalanceSheetResponse balanceSheet(
            @RequestParam(value = "asOf", required = false) LocalDate asOf,
            @RequestParam(value = "branchId", required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return financeReportsService.balanceSheet(TenantRequestIds.resolveBusinessId(request), asOf, branchId);
    }
}
