package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Simple period P&amp;L sourced from {@code journal_lines} (PHASE_7_PLAN.md Slice 0,
 * implement.md §9.5). Phase 7 v1 uses the journal as single source of truth — Phase 7
 * Slice 2 layers an MV over the same numbers for speed.
 */
public record ProfitAndLossResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal grossProfit,
        BigDecimal operatingExpenses,
        BigDecimal netOperating,
        List<LineItem> revenueLines,
        List<LineItem> cogsLines,
        List<LineItem> expenseLines
) {

    public record LineItem(String accountCode, String accountName, BigDecimal amount) {
    }
}
