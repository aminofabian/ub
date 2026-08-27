package zelisline.ub.onboarding.sequence.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingSequenceService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.onboarding.sequence.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MerchantOnboardingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardingScheduler.class);

    private final MerchantOnboardingSequenceService sequenceService;

    /** Hourly UTC tick — due windows evaluated in each business timezone. */
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void tick() {
        int n = sequenceService.processDueBatch();
        if (n > 0) {
            log.info("onboarding sequence tick processed={}", n);
        }
    }
}
