package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.NotificationCampaignService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.promo-campaigns.enabled", havingValue = "true")
public class PromoCampaignScheduler {

    private final NotificationCampaignService campaignService;

    @Scheduled(fixedDelayString = "${app.notifications.promo-campaigns.poll-ms:60000}")
    public void pollScheduledCampaigns() {
        campaignService.dispatchDueScheduledCampaigns();
    }
}
