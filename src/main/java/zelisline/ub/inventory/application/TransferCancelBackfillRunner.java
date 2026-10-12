package zelisline.ub.inventory.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the transfer-cancel backfill once at startup when
 * {@code app.inventory.transfer-cancel-backfill.enabled=true}. Off by default — flip it on for a
 * single deploy to repair transfers cancelled before the source-restore fix, then remove it.
 * Repairs every tenant (the service is idempotent, so a second run is a no-op).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.inventory.transfer-cancel-backfill.enabled", havingValue = "true")
public class TransferCancelBackfillRunner implements ApplicationRunner {

    private final TransferCancelBackfillService backfillService;

    @Override
    public void run(ApplicationArguments args) {
        TransferCancelBackfillService.BackfillSummary summary = backfillService.run(null, null);
        log.info("Transfer-cancel backfill complete: {}", summary);
    }
}
