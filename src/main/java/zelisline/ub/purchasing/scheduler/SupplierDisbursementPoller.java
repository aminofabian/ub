package zelisline.ub.purchasing.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.purchasing.application.SupplierDisbursementService;

/**
 * Polls open supplier Send Money disbursements when webhooks are delayed, missed,
 * or consumed too early (intermediate Pending/Processed callbacks). Critical for
 * auto-pay, which has no UI status poller.
 */
@Component
public class SupplierDisbursementPoller {

    private static final Logger log = LoggerFactory.getLogger(SupplierDisbursementPoller.class);

    private final SupplierDisbursementService disbursementService;

    @Value("${app.payments.send-money.poll.max-age-minutes:45}")
    private int maxAgeMinutes;

    public SupplierDisbursementPoller(SupplierDisbursementService disbursementService) {
        this.disbursementService = disbursementService;
    }

    @Scheduled(fixedDelayString = "${app.payments.send-money.poll.interval-ms:30000}")
    public void pollPending() {
        Instant cutoff = Instant.now().minus(Math.max(maxAgeMinutes, 5), ChronoUnit.MINUTES);
        try {
            int settled = disbursementService.pollOpenDisbursements(cutoff);
            if (settled > 0) {
                log.info("Supplier Send Money poll settled {} disbursement(s)", settled);
            }
        } catch (Exception e) {
            log.warn("Supplier Send Money poll run failed: {}", e.getMessage());
        }
    }
}
