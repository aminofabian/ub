package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Whether the till (or storefront) should offer airtime right now, and the
 * numbers the seller needs on screen before they commit.
 */
public record AirtimeAvailabilityResponse(
        boolean available,
        boolean platformEnabled,
        boolean businessEnabled,
        boolean credentialsConfigured,
        boolean walletActive,
        BigDecimal walletBalance,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        /** Lowest of the platform max, tenant max, and what the wallet can cover. */
        BigDecimal maxSellableNow,
        BigDecimal commissionPercent,
        BigDecimal dailyLimit,
        BigDecimal dailyUsed,
        BigDecimal dailyRemaining,
        BigDecimal commissionEarnedToday,
        String currency,
        List<BigDecimal> quickAmounts,
        String reason
) {
}
