package zelisline.ub.billing.scheduler;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.application.SubscriptionBillingSettingsService;
import zelisline.ub.billing.application.SubscriptionExpiryCampaignService;

/**
 * Daily 08:00 EAT: send due subscription expiry campaign touches (SMS/email).
 */
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryCampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryCampaignScheduler.class);

    private final SubscriptionBillingSettingsService settingsService;
    private final SubscriptionExpiryCampaignService campaignService;

    @Scheduled(cron = "${app.subscription.expiry-campaign-cron:0 0 8 * * *}", zone = "Africa/Nairobi")
    public void processDueCampaigns() {
        if (!settingsService.isBillingEnabled()) {
            return;
        }
        int sent = campaignService.processDueCampaigns(Instant.now());
        if (sent > 0) {
            log.info("Subscription expiry campaign job: {} step(s) sent", sent);
        }
    }
}
