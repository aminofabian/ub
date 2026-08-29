package zelisline.ub.billing.scheduler;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.application.SubscriptionBillingService;
import zelisline.ub.billing.application.SubscriptionBillingSettingsService;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Hourly: ACTIVE tenants past {@code current_period_end} enter grace.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryScheduler.class);

    private final BusinessRepository businessRepository;
    private final SubscriptionBillingService billingService;
    private final SubscriptionBillingSettingsService settingsService;

    @Scheduled(cron = "${app.subscription.expiry-cron:0 0 * * * *}", zone = "Africa/Nairobi")
    public void processExpiry() {
        if (!settingsService.isBillingEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<Business> due = businessRepository.findDueForPeriodExpiry(
                SubscriptionBillingStatus.ACTIVE, now);
        int entered = 0;
        for (Business business : due) {
            try {
                if (billingService.enterGraceIfDue(business, now)) {
                    entered++;
                }
            } catch (RuntimeException ex) {
                log.error("Subscription grace entry failed business={} error={}",
                        business.getId(), ex.getMessage());
            }
        }
        if (entered > 0) {
            log.info("Subscription expiry job: {} of {} due tenants entered grace", entered, due.size());
        }
    }
}
