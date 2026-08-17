package zelisline.ub.airtime.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.airtime.application.AirtimeSaleService;

/**
 * Sweeps airtime orders the provider still owes us an answer on, so a missed
 * Instalipa callback cannot leave a merchant's wallet funds held forever.
 * Mirrors {@code KioskPayWithdrawReconciler}.
 */
@Component
public class AirtimeOrderReconciler {

    private static final Logger log = LoggerFactory.getLogger(AirtimeOrderReconciler.class);

    private final AirtimeSaleService saleService;

    public AirtimeOrderReconciler(AirtimeSaleService saleService) {
        this.saleService = saleService;
    }

    @Scheduled(fixedDelayString = "${app.airtime.reconcile.interval-ms:60000}")
    public void reconcile() {
        try {
            int changed = saleService.reconcileAllInFlight();
            if (changed > 0) {
                log.info("Airtime reconcile finalized {} order(s)", changed);
            }
        } catch (Exception e) {
            log.warn("Airtime reconcile run failed: {}", e.getMessage());
        }
    }
}
