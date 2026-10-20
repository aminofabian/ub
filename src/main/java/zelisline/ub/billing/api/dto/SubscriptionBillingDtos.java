package zelisline.ub.billing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import zelisline.ub.billing.domain.SubscriptionBillingStatus;

public final class SubscriptionBillingDtos {

    private SubscriptionBillingDtos() {}

    public record BillingStatusResponse(
            SubscriptionBillingStatus status,
            String tier,
            String tierDisplayName,
            BigDecimal amountDueKes,
            String currency,
            Instant currentPeriodEnd,
            Instant graceStartedAt,
            Instant graceEndsAt,
            int daysSinceExpiry,
            int daysRemainingInGrace,
            String renewalUrl,
            boolean billingEnabled,
            boolean autoBlockEnabled,
            /** Effective access decision from the central policy. */
            boolean accessBlocked,
            /** Latest renewal attempt status, or null when there has never been one. */
            String lastRenewalStatus,
            Instant lastRenewalAt,
            PlanFitView planFit
    ) {}

    public record PlanFitView(
            long productCount,
            long userCount,
            Integer productLimit,
            Integer userLimit,
            BigDecimal dailyRevenueKes,
            BigDecimal dailyRevenueLimit,
            boolean overProductLimit,
            boolean overUserLimit,
            boolean overDailyRevenueLimit,
            boolean needsUpgrade,
            boolean negotiable,
            boolean talkToUs,
            String recommendedTier,
            String recommendedDisplayName,
            BigDecimal recommendedPriceKes,
            java.util.List<String> reasons
    ) {}

    public record PlanResponse(
            String tierCode,
            String displayName,
            BigDecimal monthlyPriceKes,
            BigDecimal annualPriceKes,
            int graceDays,
            Integer productLimit,
            Integer cashierLimit,
            BigDecimal dailyRevenueLimitKes,
            boolean active,
            int sortOrder
    ) {}

    public record PlansResponse(java.util.List<PlanResponse> plans) {}

    public record SettingsResponse(
            boolean billingEnabled,
            boolean autoBlockEnabled,
            int defaultGraceDays,
            String renewalBaseUrl,
            String notificationCadenceDays,
            int preExpiryReminderDays,
            Instant updatedAt
    ) {}

    public record UpdateSettingsRequest(
            Boolean billingEnabled,
            Boolean autoBlockEnabled,
            Integer defaultGraceDays,
            String renewalBaseUrl,
            String notificationCadenceDays,
            Integer preExpiryReminderDays
    ) {}

    public record UpdatePlanRequest(
            String displayName,
            BigDecimal monthlyPriceKes,
            BigDecimal annualPriceKes,
            Integer graceDays,
            Integer productLimit,
            Integer cashierLimit,
            BigDecimal dailyRevenueLimitKes,
            Boolean active,
            Integer sortOrder
    ) {}

    public record ExtendSubscriptionRequest(
            int months,
            String note
    ) {}

    public record ExtendGraceRequest(
            int days,
            String note
    ) {}

    public record AssignPlanRequest(
            String tierCode,
            String note
    ) {}

    public record OverrideSubscriptionRequest(
            String tierCode,
            SubscriptionBillingStatus billingStatus,
            Instant currentPeriodEnd,
            Instant graceEndsAt,
            String note
    ) {}

    public record AdminSubscriptionSnapshot(
            String businessId,
            String tier,
            String tierDisplayName,
            SubscriptionBillingStatus billingStatus,
            Instant currentPeriodEnd,
            Instant graceStartedAt,
            Instant graceEndsAt,
            Instant billingSuspendedAt,
            String suspensionReason,
            BigDecimal amountDueKes
    ) {}

    public record RenewalQuoteResponse(
            String tier,
            String tierDisplayName,
            int periodMonths,
            BigDecimal amountKes,
            BigDecimal listPriceKes,
            BigDecimal savingsKes,
            String currency,
            Instant currentPeriodEnd,
            Instant graceEndsAt
    ) {}

    public record RenewSubscriptionRequest(
            String tier,
            Integer periodMonths,
            String phone
    ) {}

    public record RenewSubscriptionResponse(
            String orderId,
            String status,
            BigDecimal amountKes,
            String phoneNumber,
            String message
    ) {}

    public record RenewalOrderStatusResponse(
            String orderId,
            String status,
            BigDecimal amountKes,
            String mpesaReceipt,
            Instant paidAt,
            boolean needsRetry
    ) {}

    public record DunningAnalyticsResponse(
            boolean billingEnabled,
            boolean autoBlockEnabled,
            long tenantsInGrace,
            long tenantsSuspended,
            BigDecimal monthlyRevenueAtRiskKes,
            double graceRecoveryRatePercent,
            long graceEpisodesLast90d,
            long renewalsLast30d,
            BigDecimal renewalRevenueLast30dKes,
            long preExpiryRemindersLast30d,
            Instant generatedAt
    ) {}
}
