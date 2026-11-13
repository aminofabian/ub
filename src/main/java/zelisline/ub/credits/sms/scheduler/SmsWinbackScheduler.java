package zelisline.ub.credits.sms.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.sms.application.SmsAutomationService;

/**
 * Daily win-back texts at 10:00 Africa/Nairobi, inside the promotional sending window.
 * Each text is de-duplicated against the message log, so a second app instance running
 * the same tick sends nothing extra. Off on the desktop SKU.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.sms-hub.winback.enabled", havingValue = "true", matchIfMissing = true)
public class SmsWinbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsWinbackScheduler.class);

    private final SmsAutomationService automationService;

    @Async
    @Scheduled(cron = "${app.sms-hub.winback.cron:0 0 10 * * *}", zone = "Africa/Nairobi")
    public void run() {
        try {
            int sent = automationService.runWinback();
            if (sent > 0) {
                log.info("sms_winback run complete: {} sent", sent);
            }
        } catch (RuntimeException ex) {
            log.error("sms_winback run failed: {}", ex.getMessage(), ex);
        }
    }
}
