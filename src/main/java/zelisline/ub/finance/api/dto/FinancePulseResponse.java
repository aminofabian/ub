package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Owner pulse for a single business date (PHASE_7_PLAN.md Slice 0 / implement.md §9.1).
 *
 * <p>{@code date} is interpreted in UTC to match how every other module persists
 * {@code journal_entries.entry_date}; multi-timezone bucketing is a Phase 7 Slice 4 concern.</p>
 */
public record FinancePulseResponse(
        LocalDate date,
        String branchId,
        long salesCount,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal grossProfit,
        BigDecimal grossMarginPct,
        BigDecimal expensesTotal,
        BigDecimal netOperating,
        long openShifts
) {
}
