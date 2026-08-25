package zelisline.ub.tenancy.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated statistics for a single business, shown on the super-admin
 * business detail page.
 */
public record SaBusinessStatsResponse(
        long totalUsers,
        long activeUsers,
        long totalProducts,
        long webPublishedProducts,
        long totalBranches,
        long openShifts,
        SalesPulse sales,
        StorefrontPulse storefront,
        List<PaymentMethodRow> paymentMethods,
        boolean kioskPayActive,
        String kioskPayStatus,
        String onboardingStatus,
        String lastUserLoginAt,
        String lastSaleAt
) {
    public record SalesPulse(
            long salesToday,
            BigDecimal revenueToday,
            BigDecimal unitsToday,
            long salesLast7Days,
            BigDecimal revenueLast7Days,
            long salesLast30Days,
            BigDecimal revenueLast30Days,
            BigDecimal unitsLast30Days,
            long salesAllTime,
            BigDecimal revenueAllTime,
            BigDecimal unitsAllTime
    ) {}

    public record StorefrontPulse(
            long paidOrdersLast30Days,
            BigDecimal paidGmvLast30Days,
            long paidOrdersAllTime,
            BigDecimal paidGmvAllTime
    ) {}

    public record PaymentMethodRow(
            String gatewayType,
            String label,
            String status,
            boolean isDefault
    ) {}
}
