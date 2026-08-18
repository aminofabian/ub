package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/**
 * Totals for storefront (online) airtime — not capped by the activity list.
 */
public record AirtimeStorefrontSummaryResponse(
        String currency,
        BigDecimal commissionPercent,
        long successCount,
        BigDecimal successAmount,
        BigDecimal commissionEarned,
        long todaySuccessCount,
        BigDecimal todaySuccessAmount,
        BigDecimal todayCommission,
        long awaitingPaymentCount,
        long inFlightCount,
        long failedCount
) {
}
