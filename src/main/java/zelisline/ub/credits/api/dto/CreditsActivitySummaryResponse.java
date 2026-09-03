package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Period AR collections plus live open-tab total for the credits activity board.
 */
public record CreditsActivitySummaryResponse(
        BigDecimal totalPaid,
        long paymentCount,
        BigDecimal totalOwed,
        long openTabCount,
        List<CreditCollectionRowResponse> collections
) {
}
