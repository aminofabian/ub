package zelisline.ub.desktop.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import zelisline.ub.platform.realtime.RealtimeBridge;

/**
 * Desktop-only hook that pushes a sale to the shop's online instance the moment
 * it completes at the till — the "realtime" half of desktop sync.
 *
 * <p>Every completed sale publishes {@link RealtimeBridge.SaleCompletedEvent};
 * on a desktop install this listener turns that into an immediate
 * {@link DesktopSyncPushService#pushPending()} run, so the online shop (and
 * anyone looking at the cloud dashboards) sees the sale within seconds instead
 * of waiting for shift close.
 *
 * <p>Offline-safe: a failed push leaves the sale's {@code cloud_synced_at}
 * marker untouched, so it stays pending and is retried by the next sale, the
 * periodic flush ({@link DesktopSyncScheduler}), shift close, or a manual sync.
 * Runs async off the sale-completion request and never blocks the till on the
 * network.
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSaleCompletedSyncListener {

    private static final Logger log = LoggerFactory.getLogger(DesktopSaleCompletedSyncListener.class);

    private final DesktopSyncPushService syncPushService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(RealtimeBridge.SaleCompletedEvent event) {
        try {
            DesktopSyncPushService.SyncPushResult result = syncPushService.pushPending();
            if (result.salesPushed() > 0 || result.shiftsPushed() > 0) {
                log.info(
                    "[DesktopSync] sale {} completed — pushed {} sale(s) in {} shift(s) to the online shop",
                    event.saleId(), result.salesPushed(), result.shiftsPushed()
                );
            }
        } catch (Exception e) {
            log.debug(
                "[DesktopSync] realtime push for sale {} failed (stays pending; retried by the next trigger): {}",
                event.saleId(), e.getMessage()
            );
        }
    }
}
