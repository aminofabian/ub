package zelisline.ub.purchasing.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.application.SupplierAutoPayService;

/**
 * Twice-daily auto-pay for unpaid supply bills (midnight and 18:00 Africa/Nairobi by default).
 *
 * <p>Per-tenant opt-in via Payments → Supplier payouts → Auto-pay. No distributed lock
 * (same as other schedulers in this codebase); avoid running multiple API replicas that
 * both enable this cron if double-initiate risk is unacceptable.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.purchasing.supplier-auto-pay.enabled", havingValue = "true", matchIfMissing = true)
public class SupplierAutoPayScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupplierAutoPayScheduler.class);

    private final SupplierAutoPayService supplierAutoPayService;

    @Scheduled(
            cron = "${app.purchasing.supplier-auto-pay.cron:0 0 0,18 * * *}",
            zone = "${app.purchasing.supplier-auto-pay.zone:Africa/Nairobi}")
    public void runTwiceDaily() {
        log.info("Supplier auto-pay scheduler starting");
        var summary = supplierAutoPayService.runScheduledAutoPay();
        log.info(
                "Supplier auto-pay scheduler finished: businesses={} initiated={} skipped={} failed={}",
                summary.businesses(),
                summary.initiated(),
                summary.skipped(),
                summary.failed());
    }
}
