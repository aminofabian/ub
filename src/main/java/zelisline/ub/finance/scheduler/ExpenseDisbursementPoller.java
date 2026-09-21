package zelisline.ub.finance.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.finance.application.ExpenseDisbursementService;

/**
 * Polls open expense Send Money disbursements when webhooks are delayed or missed.
 */
@Component
public class ExpenseDisbursementPoller {

    private static final Logger log = LoggerFactory.getLogger(ExpenseDisbursementPoller.class);

    private final ExpenseDisbursementService disbursementService;

    @Value("${app.payments.send-money.poll.max-age-minutes:45}")
    private int maxAgeMinutes;

    public ExpenseDisbursementPoller(ExpenseDisbursementService disbursementService) {
        this.disbursementService = disbursementService;
    }

    @Scheduled(fixedDelayString = "${app.payments.send-money.poll.interval-ms:30000}")
    public void pollPending() {
        Instant cutoff = Instant.now().minus(Math.max(maxAgeMinutes, 5), ChronoUnit.MINUTES);
        try {
            int settled = disbursementService.pollOpenDisbursements(cutoff);
            if (settled > 0) {
                log.info("Expense Send Money poll settled {} disbursement(s)", settled);
            }
        } catch (Exception e) {
            log.warn("Expense Send Money poll run failed: {}", e.getMessage());
        }
    }
}
