package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Simple as-of balance sheet sourced from {@code journal_lines} aggregated by account type
 * (PHASE_7_PLAN.md Slice 0, implement.md §9.5). The "current period earnings" line under
 * equity rolls revenue minus expenses up to {@code asOf} so the identity
 * {@code totalAssets = totalLiabilities + totalEquity} holds without an explicit
 * period-close journal entry.
 */
public record BalanceSheetResponse(
        LocalDate asOf,
        String branchId,
        List<LineItem> assets,
        List<LineItem> liabilities,
        List<LineItem> equity,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        BigDecimal totalLiabilitiesAndEquity,
        boolean balanced
) {

    public record LineItem(String accountCode, String accountName, BigDecimal amount) {
    }
}
