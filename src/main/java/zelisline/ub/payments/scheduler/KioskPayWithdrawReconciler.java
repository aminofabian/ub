package zelisline.ub.payments.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.payments.application.KioskPayWithdrawService;

/**
 * Sweeps stuck Kiosk Pay withdrawals (REQUESTED / PROCESSING) so a missed Send
 * Money webhook cannot block the merchant's next withdraw forever. Mirrors
 * {@link GatewayStkPushPoller} / {@link GatewayCheckoutReconciler}.
 */
@Component
public class KioskPayWithdrawReconciler {

    private static final Logger log = LoggerFactory.getLogger(KioskPayWithdrawReconciler.class);

    private final KioskPayWithdrawService withdrawService;

    public KioskPayWithdrawReconciler(KioskPayWithdrawService withdrawService) {
        this.withdrawService = withdrawService;
    }

    @Scheduled(fixedDelayString = "${app.payments.kiosk-pay.withdraw.reconcile.interval-ms:60000}")
    public void reconcile() {
        try {
            int changed = withdrawService.reconcileAllInFlight();
            if (changed > 0) {
                log.info("Kiosk Pay withdraw reconcile finalized {} row(s)", changed);
            }
        } catch (Exception e) {
            log.warn("Kiosk Pay withdraw reconcile run failed: {}", e.getMessage());
        }
    }
}
