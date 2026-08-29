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
 * Daily 09:00 EAT: GRACE tenants past {@code grace_ends_at} are suspended.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionGraceEndScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionGraceEndScheduler.class);

    private final BusinessRepository businessRepository;
    private final SubscriptionBillingService billingService;
    private final SubscriptionBillingSettingsService settingsService;

    @Scheduled(cron = "${app.subscription.grace-end-cron:0 0 9 * * *}", zone = "Africa/Nairobi")
    public void processGraceEnd() {
        if (!settingsService.isBillingEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<Business> due = businessRepository.findDueForGraceEnd(SubscriptionBillingStatus.GRACE, now);
        int suspended = 0;
        for (Business business : due) {
            try {
                if (billingService.suspendIfGraceEnded(business, now)) {
                    suspended++;
                }
            } catch (RuntimeException ex) {
                log.error("Subscription suspension failed business={} error={}",
                        business.getId(), ex.getMessage());
            }
        }
        if (suspended > 0) {
            log.info("Subscription grace-end job: {} of {} due tenants suspended", suspended, due.size());
        }
    }
}
