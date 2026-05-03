package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record CreditAccountSummaryResponse(
        BigDecimal balanceOwed,
        BigDecimal walletBalance,
        int loyaltyPoints,
        BigDecimal creditLimit,
        long version
) {
}
