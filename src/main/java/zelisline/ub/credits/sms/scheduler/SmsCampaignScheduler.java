package zelisline.ub.credits.sms.scheduler;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.sms.application.CustomerSmsCampaignRunner;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;
import zelisline.ub.credits.sms.repository.CustomerSmsCampaignRepository;

/**
 * Picks up SMS campaigns that are queued or due and hands each to the runner. The
 * claim is an atomic status update, so with several app instances exactly one sends a
 * given campaign. Off on the desktop SKU, which holds no SMS credentials.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.sms-hub.campaigns.enabled", havingValue = "true", matchIfMissing = true)
public class SmsCampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsCampaignScheduler.class);
    private static final int BATCH = 5;

    private final CustomerSmsCampaignRepository campaignRepository;
    private final CustomerSmsCampaignRunner runner;

    @Scheduled(
            fixedDelayString = "${app.sms-hub.campaigns.poll-ms:30000}",
            initialDelayString = "${app.sms-hub.campaigns.initial-delay-ms:60000}")
    public void pollDueCampaigns() {
        try {
            runner.recoverStalled();
            Instant now = Instant.now();
            List<CustomerSmsCampaign> due = campaignRepository.findDue(now, PageRequest.of(0, BATCH));
            for (CustomerSmsCampaign campaign : due) {
                if (campaignRepository.claim(campaign.getId(), now) == 1) {
                    runner.runAsync(campaign.getId());
                }
            }
        } catch (RuntimeException ex) {
            log.error("sms_campaign poll failed: {}", ex.getMessage(), ex);
        }
    }
}
