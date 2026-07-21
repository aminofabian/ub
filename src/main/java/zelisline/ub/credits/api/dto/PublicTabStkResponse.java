package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record PublicTabStkResponse(
        String intentId,
        String checkoutRequestId,
        String status,
        BigDecimal amount,
        BigDecimal balanceOwed
) {
}
