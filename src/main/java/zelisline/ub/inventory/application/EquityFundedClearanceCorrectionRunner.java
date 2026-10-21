package zelisline.ub.inventory.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the equity-funded clearance correction once at startup when
 * {@code app.inventory.equity-clearance-correction.enabled=true}. Off by default —
 * flip it on for a single deploy to repair historical shrinkage misposts, then
 * turn it off. Idempotent across tenants.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.inventory.equity-clearance-correction.enabled", havingValue = "true")
public class EquityFundedClearanceCorrectionRunner implements ApplicationRunner {

    private final EquityFundedClearanceCorrectionService correctionService;

    @Override
    public void run(ApplicationArguments args) {
        EquityFundedClearanceCorrectionService.CorrectionSummary summary =
                correctionService.apply(null);
        log.info("Equity-funded clearance correction complete: {}", summary);
    }
}
