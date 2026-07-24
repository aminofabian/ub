package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

/**
 * Period AR collections plus live open-tab total for the credits activity board.
 */
public record CreditsActivitySummaryResponse(
        BigDecimal totalPaid,
        long paymentCount,
        BigDecimal totalOwed,
        long openTabCount
) {
}
