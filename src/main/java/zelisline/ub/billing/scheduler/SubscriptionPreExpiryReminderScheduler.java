package zelisline.ub.billing.scheduler;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.application.SubscriptionBillingSettingsService;
import zelisline.ub.billing.application.SubscriptionPreExpiryReminderService;

/**
 * Daily 07:00 EAT: email tenants N days before subscription period end.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionPreExpiryReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPreExpiryReminderScheduler.class);

    private final SubscriptionBillingSettingsService settingsService;
    private final SubscriptionPreExpiryReminderService reminderService;

    @Scheduled(cron = "${app.subscription.pre-expiry-cron:0 0 7 * * *}", zone = "Africa/Nairobi")
    public void processDueReminders() {
        if (!settingsService.isBillingEnabled()) {
            return;
        }
        int sent = reminderService.processDueReminders(Instant.now());
        if (sent > 0) {
            log.info("Subscription pre-expiry reminder job: {} reminder(s) sent", sent);
        }
    }
}
