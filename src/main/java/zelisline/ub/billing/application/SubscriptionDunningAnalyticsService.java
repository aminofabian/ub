package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.billing.domain.SubscriptionExpiryCampaignStatus;
import zelisline.ub.billing.domain.SubscriptionPreExpiryNotificationStatus;
import zelisline.ub.billing.domain.SubscriptionRenewalOrderStatus;
import zelisline.ub.billing.repository.SubscriptionExpiryCampaignRepository;
import zelisline.ub.billing.repository.SubscriptionPreExpiryNotificationRepository;
import zelisline.ub.billing.repository.SubscriptionRenewalOrderRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Dunning metrics for Super Admin — revenue at risk, grace recovery, recent renewals.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionDunningAnalyticsService {

    private final BusinessRepository businessRepository;
    private final SubscriptionBillingSettingsService settingsService;
    private final SubscriptionRenewalOrderRepository renewalOrderRepository;
    private final SubscriptionExpiryCampaignRepository campaignRepository;
    private final SubscriptionPreExpiryNotificationRepository preExpiryNotificationRepository;

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.DunningAnalyticsResponse snapshot() {
        Instant now = Instant.now();
        Instant since30d = now.minus(30, ChronoUnit.DAYS);
        Instant since90d = now.minus(90, ChronoUnit.DAYS);

        long graceCount = businessRepository.countPaidTierByBillingStatus(SubscriptionBillingStatus.GRACE);
        long suspendedCount = businessRepository.countPaidTierByBillingStatus(SubscriptionBillingStatus.SUSPENDED);
        BigDecimal revenueAtRisk = sumMonthlyAtRisk(List.of(
                SubscriptionBillingStatus.GRACE,
                SubscriptionBillingStatus.SUSPENDED));

        long campaignsCancelled = campaignRepository.countByStatusAndCreatedAtAfter(
                SubscriptionExpiryCampaignStatus.CANCELLED, since90d);
        long campaignsCompleted = campaignRepository.countByStatusAndCreatedAtAfter(
                SubscriptionExpiryCampaignStatus.COMPLETED, since90d);
        long graceEpisodes = campaignsCancelled + campaignsCompleted;
        double graceRecoveryRate = graceEpisodes > 0
                ? (campaignsCancelled * 100.0) / graceEpisodes
                : 0.0;

        long renewalsLast30d = renewalOrderRepository.countByStatusAndPaidAtAfter(
                SubscriptionRenewalOrderStatus.PAID, since30d);
        BigDecimal renewalRevenueLast30d = renewalOrderRepository.sumAmountByStatusAndPaidAtAfter(
                SubscriptionRenewalOrderStatus.PAID, since30d);

        long preExpirySentLast30d = preExpiryNotificationRepository.countByStatusAndSentAtAfter(
                SubscriptionPreExpiryNotificationStatus.SENT, since30d);

        return new SubscriptionBillingDtos.DunningAnalyticsResponse(
                settingsService.isBillingEnabled(),
                graceCount,
                suspendedCount,
                revenueAtRisk,
                Math.round(graceRecoveryRate * 10) / 10.0,
                graceEpisodes,
                renewalsLast30d,
                renewalRevenueLast30d != null ? renewalRevenueLast30d : BigDecimal.ZERO,
                preExpirySentLast30d,
                now);
    }

    private BigDecimal sumMonthlyAtRisk(List<SubscriptionBillingStatus> statuses) {
        BigDecimal total = BigDecimal.ZERO;
        for (SubscriptionBillingStatus status : statuses) {
            for (Business business : businessRepository.findPaidTierByBillingStatus(status)) {
                PlatformSubscriptionPlan plan = settingsService.planOrNull(business.getSubscriptionTier());
                if (plan != null && plan.getMonthlyPriceKes() != null) {
                    total = total.add(plan.getMonthlyPriceKes());
                }
            }
        }
        return total;
    }
}
