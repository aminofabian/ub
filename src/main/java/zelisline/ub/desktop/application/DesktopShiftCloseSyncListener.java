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
 * Desktop-only hook that makes closing a shift push the shift's final state
 * (closing cash / variance) — plus any straggler sales that weren't uploaded
 * yet — to the shop's online instance immediately.
 *
 * <p>Everyday sales are already pushed in realtime by
 * {@link DesktopSaleCompletedSyncListener}, so by close time most shifts are
 * synced; this hook is the safety net that guarantees the shift record (with
 * its closing figures) reaches the cloud and that an offline stretch at close
 * is caught. The push is idempotent
 * ({@link DesktopSyncPushService#pushPending()} stamps the per-sale
 * {@code cloud_synced_at} markers only after the cloud acknowledges the
 * batch), so a failed push is safely retried by the next shift close, the
 * periodic flush, or manual sync.
 *
 * <p>Runs async off the shift-close request; failures are logged and leave the
 * shifts pending — the till never blocks on the network at close.
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopShiftCloseSyncListener {

    private static final Logger log = LoggerFactory.getLogger(DesktopShiftCloseSyncListener.class);

    private final DesktopSyncPushService syncPushService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftClosed(RealtimeBridge.ShiftClosedEvent event) {
        try {
            DesktopSyncPushService.SyncPushResult result = syncPushService.pushPending();
            log.info(
                "[DesktopSync] shift close triggered push: {} shift(s), {} sale(s) pushed (configured={})",
                result.shiftsPushed(), result.salesPushed(), result.configured()
            );
        } catch (Exception e) {
            log.warn(
                "[DesktopSync] shift-close push failed (shifts stay pending; retried on next sync): {}",
                e.getMessage()
            );
        }
    }
}
