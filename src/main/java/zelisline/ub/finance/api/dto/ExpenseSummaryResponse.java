package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregate view of expenses for a period, independent of the paginated list.
 * Totals include only the rows matching the request filters; posted/pending/
 * rejected are split so the UI never mixes unposted money into ledger figures.
 */
public record ExpenseSummaryResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        BigDecimal totalAmount,
        BigDecimal postedAmount,
        BigDecimal pendingAmount,
        BigDecimal rejectedAmount,
        BigDecimal postedFixedAmount,
        BigDecimal postedVariableAmount,
        int totalCount,
        int postedCount,
        int pendingCount,
        int rejectedCount,
        List<CategoryTotal> categories
) {
    public record CategoryTotal(
            String categoryCode,
            BigDecimal postedAmount,
            BigDecimal pendingAmount,
            BigDecimal rejectedAmount,
            int count
    ) {
    }
}
