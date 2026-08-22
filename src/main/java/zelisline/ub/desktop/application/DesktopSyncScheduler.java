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
 * accumulated while it was offline — and pulls back sales made elsewhere
 * (web portal / other tills) so the till always mirrors the whole shop.
 *
 * <p>Both runs are no-ops when the outboxes are empty, cheap when they aren't,
 * and never raise — an unreachable online shop just leaves the work pending
 * for the next run (per-sale {@code cloud_synced_at} markers are only stamped
 * after the cloud acknowledges).
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncScheduler.class);

    private final DesktopSyncPushService syncPushService;
    private final DesktopSyncPullService syncPullService;

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
            // Down first (cloud sales -> this till), then up (this till's sales
            // -> cloud): a sale made in the web portal lands on the till within
            // a couple of minutes, and vice versa.
            int pulled = syncPullService.pullCloudSales();
            DesktopSyncPushService.SyncPushResult push = syncPushService.pushPending();
            if (pulled > 0 || push.shiftsPushed() > 0 || push.salesPushed() > 0) {
                log.info(
                    "[DesktopSync] {} flush: pulled {} cloud sale(s), pushed {} sale(s) in {} shift(s)",
                    reason, pulled, push.salesPushed(), push.shiftsPushed()
                );
            } else if (push.configured()) {
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
