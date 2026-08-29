package zelisline.ub.messaging.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tenant balance view — powers the header chip and buy dialog
 * (SMS_CREDITS_SCOPE.md §10).
 */
public record SmsCreditBalanceResponse(
        int available,
        int includedRemaining,
        int includedAllowance,
        int purchasedBalance,
        Instant cycleEndsAt,
        BigDecimal unitPriceKes,
        boolean lowBalance,
        boolean meteringEnabled,
        int minPurchaseCredits,
        int maxPurchaseCredits
) {
}
