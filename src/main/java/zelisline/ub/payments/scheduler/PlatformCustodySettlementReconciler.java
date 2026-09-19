package zelisline.ub.payments.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.payments.application.PlatformCustodySettlementService;

@Component
public class PlatformCustodySettlementReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlatformCustodySettlementReconciler.class);

    private final PlatformCustodySettlementService custodySettlementService;

    public PlatformCustodySettlementReconciler(PlatformCustodySettlementService custodySettlementService) {
        this.custodySettlementService = custodySettlementService;
    }

    @Scheduled(fixedDelayString = "${app.payments.platform-custody.reconcile.interval-ms:60000}")
    public void reconcile() {
        try {
            custodySettlementService.reconcilePending();
        } catch (Exception e) {
            log.warn("Platform custody reconcile (pending) failed: {}", e.getMessage());
        }
        try {
            custodySettlementService.reconcileInFlight();
        } catch (Exception e) {
            log.warn("Platform custody reconcile (in-flight) failed: {}", e.getMessage());
        }
    }
}
