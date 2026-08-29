package zelisline.ub.messaging.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.application.SmsCreditDigestService;

/**
 * Daily 80%/100% SMS usage email digest — 07:30 Africa/Nairobi
 * (SMS_CREDITS_SCOPE.md §17).
 */
@Component
@RequiredArgsConstructor
public class SmsCreditDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsCreditDigestScheduler.class);

    private final SmsCreditDigestService digestService;

    @Scheduled(cron = "${app.sms-credits.digest-cron:0 30 7 * * *}", zone = "Africa/Nairobi")
    public void tick() {
        int emailed = digestService.processDueDigests();
        if (emailed > 0) {
            log.info("SMS credit usage digest sent to {} business(es)", emailed);
        }
    }
}
