package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of the ledger P&amp;L: {@code netOperating = revenue − cogs − operatingExpenses}.
 * Zero-filled for days with no journal activity so the strip has a continuous axis.
 */
public record DailyProfitPoint(
        LocalDate date,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal grossProfit,
        BigDecimal operatingExpenses,
        BigDecimal netOperating,
        /** False when the day had no journal activity at all (unlike a genuine 0 net). */
        boolean hasActivity
) {
}
