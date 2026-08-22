package zelisline.ub.desktop.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Desktop-only catch-up for the store-and-forward outbox: flushes pending
 * sales when the till starts, then re-tries every couple of minutes so a till
 * that comes online mid-shift (or comes back from an outage) pushes what
 * accumulated while it was offline.
 *
 * <p>Both runs are no-ops when the outbox is empty, cheap when it isn't, and
 * never raise — an unreachable online shop just leaves the sales pending for
 * the next run (the per-sale {@code cloud_synced_at} markers are only stamped
 * after the cloud acknowledges).
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncScheduler.class);

    private final DesktopSyncPushService syncPushService;

    @EventListener(ApplicationReadyEvent.class)
    public void flushOnStartup() {
        flush("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.desktop.sync.retry-interval-ms:120000}",
            initialDelayString = "${app.desktop.sync.retry-initial-delay-ms:15000}")
    public void scheduledFlush() {
        flush("scheduled");
    }

    private void flush(String reason) {
        try {
            DesktopSyncPushService.SyncPushResult result = syncPushService.pushPending();
            if (result.shiftsPushed() > 0 || result.salesPushed() > 0) {
                log.info(
                    "[DesktopSync] {} flush pushed {} sale(s) in {} shift(s) to the online shop",
                    reason, result.salesPushed(), result.shiftsPushed()
                );
            } else if (result.configured()) {
                log.debug("[DesktopSync] {} flush: nothing pending", reason);
            }
        } catch (Exception e) {
            log.debug(
                "[DesktopSync] {} flush could not reach the online shop (offline?): {}",
                reason, e.getMessage()
            );
        }
    }
}
