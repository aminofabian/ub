package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.InsightsDigestService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.insights.enabled", havingValue = "true")
public class InsightsDigestScheduler {

    private final InsightsDigestService insightsDigestService;

    @Scheduled(
            cron = "${app.notifications.insights.abandoned-cart.cron:0 0 9 * * *}",
            zone = "${app.notifications.insights.zone:Africa/Nairobi}"
    )
    public void abandonedCartDigests() {
        insightsDigestService.enqueueAbandonedCartDigests();
    }

    @Scheduled(
            cron = "${app.notifications.insights.peak-hours.cron:0 0 8 * * *}",
            zone = "${app.notifications.insights.zone:Africa/Nairobi}"
    )
    public void peakHoursDigests() {
        insightsDigestService.enqueuePeakHoursDigests();
    }

    @Scheduled(
            cron = "${app.notifications.insights.top-products.cron:0 0 7 * * MON}",
            zone = "${app.notifications.insights.zone:Africa/Nairobi}"
    )
    public void topProductsDigests() {
        insightsDigestService.enqueueTopProductsDigests();
    }

    @Scheduled(
            cron = "${app.notifications.win-back.cron:0 0 10 * * MON}",
            zone = "${app.notifications.insights.zone:Africa/Nairobi}"
    )
    public void winBackCampaign() {
        insightsDigestService.enqueueWinBackCampaign();
    }
}
