package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Current expected per-denomination drawer balance for a shift, computed by
 * projecting {@code cash_drawer_movements} over the opening count.
 */
public record DrawerBalanceResponse(
        String shiftId,
        String branchId,
        String openedBy,
        BigDecimal expectedClosingCash,
        BigDecimal ledgerTotal,
        /** True when the ledger projection reconciles to expected_closing_cash within tolerance. */
        boolean consistent,
        List<DenominationBalanceRow> balances
) {

    public record DenominationBalanceRow(
            int denomination,
            String denominationType,
            int quantity,
            BigDecimal total
    ) {
    }
}
