package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;

public record ExpenseCalendarMonthResponse(
        int month,
        String status,
        int dueCount,
        int postedCount,
        int failedCount,
        int skippedCount,
        BigDecimal commitment,
        BigDecimal postedTotal
) {
}
