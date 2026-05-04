package zelisline.ub.reporting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 7 Slice 2 sales register response — Report #2 in PHASE_7_PLAN.md.
 *
 * <p>One row per (day, branch). Branch is included in the row even when the request
 * spans branches so the client can drill down without a second call. Totals across the
 * whole window are surfaced at the top level.</p>
 */
public record SalesRegisterResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        List<Day> days,
        BigDecimal totalQty,
        BigDecimal totalRevenue,
        BigDecimal totalCost,
        BigDecimal totalProfit
) {

    public record Day(
            LocalDate day,
            String branchId,
            BigDecimal qty,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal profit
    ) {
    }
}
