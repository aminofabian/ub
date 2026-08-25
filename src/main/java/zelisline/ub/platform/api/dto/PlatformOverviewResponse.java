package zelisline.ub.platform.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Super-admin console home: fleet health, signup friction, and commerce pulse.
 */
public record PlatformOverviewResponse(
        TenantFleet tenants,
        StuckSignups stuckSignups,
        CommercePulse commerce,
        StorefrontPulse storefront,
        SupportPulse support,
        List<BestSellerRow> bestSellers,
        List<HotTenantRow> hotTenants,
        List<DayBucket> last14Days,
        List<RecentTenantRow> recentTenants
) {
    public record TenantFleet(
            long total,
            long active,
            long inactive,
            long createdLast7Days,
            long kioskPayActive
    ) {}

    public record StuckSignups(
            long total,
            List<StuckRow> sample
    ) {}

    public record StuckRow(
            String businessId,
            String businessName,
            String slug,
            String email,
            String name,
            String onboardingStatus,
            String continueKind,
            String lastLoginAt
    ) {}

    public record CommercePulse(
            long salesToday,
            BigDecimal revenueToday,
            BigDecimal unitsSoldToday,
            long salesLast30Days,
            BigDecimal revenueLast30Days,
            BigDecimal unitsSoldLast30Days,
            BigDecimal unitsSoldAllTime,
            long salesAllTime,
            BigDecimal revenueAllTime
    ) {}

    public record StorefrontPulse(
            long paidOrdersLast30Days,
            BigDecimal paidGmvLast30Days,
            BigDecimal unitsSoldLast30Days,
            long paidOrdersAllTime,
            BigDecimal paidGmvAllTime
    ) {}

    public record SupportPulse(
            long openTenantThreads,
            long openVisitorThreads,
            long waitingOnAdmin
    ) {}

    public record BestSellerRow(
            String itemId,
            String itemName,
            String businessId,
            String businessName,
            BigDecimal unitsSold,
            BigDecimal revenue,
            long saleCount
    ) {}

    public record HotTenantRow(
            String businessId,
            String businessName,
            String slug,
            long salesLast7Days,
            BigDecimal revenueLast7Days,
            BigDecimal unitsLast7Days
    ) {}

    public record DayBucket(
            String day,
            long sales,
            BigDecimal revenue,
            BigDecimal units
    ) {}

    public record RecentTenantRow(
            String id,
            String name,
            String slug,
            boolean active,
            String subscriptionTier,
            String createdAt
    ) {}
}
