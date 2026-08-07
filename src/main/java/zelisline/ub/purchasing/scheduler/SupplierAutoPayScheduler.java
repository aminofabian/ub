package zelisline.ub.purchasing.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.purchasing.application.SupplierAutoPayService;

/**
 * Minute tick for supplier auto-pay. Each tenant configures their own HH:mm times
 * (default 00:00 and 18:00 Africa/Nairobi). The tick claims a slot per business so
 * each configured minute runs at most once.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.purchasing.supplier-auto-pay.enabled", havingValue = "true", matchIfMissing = true)
public class SupplierAutoPayScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupplierAutoPayScheduler.class);

    private final SupplierAutoPayService supplierAutoPayService;

    @Scheduled(
            cron = "${app.purchasing.supplier-auto-pay.tick-cron:0 * * * * *}",
            zone = "${app.purchasing.supplier-auto-pay.zone:Africa/Nairobi}")
    public void tick() {
        var summary = supplierAutoPayService.runScheduledAutoPay();
        if (summary.businesses() > 0) {
            log.info(
                    "Supplier auto-pay tick: businessesDue={} initiated={} skipped={} failed={}",
                    summary.businesses(),
                    summary.initiated(),
                    summary.skipped(),
                    summary.failed());
        }
    }
}
