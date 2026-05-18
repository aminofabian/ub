package zelisline.ub.tenancy.api.dto;

import java.math.BigDecimal;

/**
 * Aggregated statistics for a single business, shown on the super-admin
 * business detail page.
 */
public record SaBusinessStatsResponse(
        long totalUsers,
        long activeUsers,
        long totalProducts,
        long totalBranches,
        long totalSalesToday,
        BigDecimal revenueToday,
        long totalSalesThisMonth,
        BigDecimal revenueThisMonth,
        long openShifts
) {
}
