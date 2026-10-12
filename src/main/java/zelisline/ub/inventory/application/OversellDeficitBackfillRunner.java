package zelisline.ub.inventory.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the oversell-deficit backfill once at startup when
 * {@code app.inventory.oversell-backfill.enabled=true}. Off by default — flip it on for a single
 * deploy to repair historical drift, then remove it. Repairs every tenant (the service is
 * idempotent, so a second run is a no-op).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.inventory.oversell-backfill.enabled", havingValue = "true")
public class OversellDeficitBackfillRunner implements ApplicationRunner {

    private final OversellDeficitBackfillService backfillService;

    @Override
    public void run(ApplicationArguments args) {
        OversellDeficitBackfillService.BackfillSummary summary = backfillService.run(null);
        log.info("Oversell deficit backfill complete: {}", summary);
    }
}
