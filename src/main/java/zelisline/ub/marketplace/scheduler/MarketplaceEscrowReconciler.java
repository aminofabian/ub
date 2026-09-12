package zelisline.ub.marketplace.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.marketplace.application.MarketplaceEscrowService;

/**
 * Sweeps SETTLING / RELEASE_QUEUED escrow holds so a missed platform Send Money
 * webhook cannot leave supplier-bound funds stuck in Kiosk Pay pending.
 */
@Component
public class MarketplaceEscrowReconciler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEscrowReconciler.class);

    private final MarketplaceEscrowService escrowService;

    public MarketplaceEscrowReconciler(MarketplaceEscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @Scheduled(fixedDelayString = "${app.payments.marketplace-escrow.reconcile.interval-ms:60000}")
    public void reconcile() {
        try {
            int changed = escrowService.reconcileAllInFlight();
            if (changed > 0) {
                log.info("Marketplace escrow reconcile finalized {} row(s)", changed);
            }
        } catch (Exception e) {
            log.warn("Marketplace escrow reconcile run failed: {}", e.getMessage());
        }
    }
}
