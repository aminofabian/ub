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
 * Desktop-only sync catch-up.
 *
 * <ul>
 *   <li>On boot: refresh the stored online-shop session, then kick a full
 *       catalog/staff sync in the background (same work as Settings → Sync now).</li>
 *   <li>Every couple of minutes: push/pull sales, supplies, web orders, and
 *       Talk to Us messages.</li>
 *   <li>Every half hour: another quiet full sync so product/price/staff changes
 *       land without the merchant pressing Sync.</li>
 * </ul>
 *
 * <p>All runs are no-ops when the till is not connected, cheap when there is
 * nothing pending, and never raise — an unreachable online shop leaves work
 * for the next run.
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncScheduler.class);

    private final CloudSyncSession cloudSyncSession;
    private final DesktopFullSyncService fullSyncService;
    private final DesktopSyncPushService syncPushService;
    private final DesktopSyncPullService syncPullService;
    private final DesktopMessagePushService messagePushService;
    private final DesktopMessagePullService messagePullService;

    @EventListener(ApplicationReadyEvent.class)
    public void flushOnStartup() {
        if (cloudSyncSession.load().isEmpty()) {
            log.debug("[DesktopSync] startup: not connected to an online shop yet");
            return;
        }
        fullSyncService.refreshSessionQuietly();
        boolean started = fullSyncService.startFullSync(false);
        if (started) {
            log.info("[DesktopSync] startup: full sync started in the background");
        }
        // Incremental flush still runs so sales land even if full sync is busy.
        flush("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.desktop.sync.retry-interval-ms:120000}",
            initialDelayString = "${app.desktop.sync.retry-initial-delay-ms:15000}")
    public void scheduledFlush() {
        flush("scheduled");
    }

    /**
     * Periodic master-data refresh. Default every 30 minutes so cloud catalog
     * and staff password/PIN changes reach the till without a manual Sync.
     */
    @Scheduled(
            fixedDelayString = "${app.desktop.sync.full-interval-ms:1800000}",
            initialDelayString = "${app.desktop.sync.full-initial-delay-ms:300000}")
    public void scheduledFullSync() {
        if (cloudSyncSession.load().isEmpty()) {
            return;
        }
        fullSyncService.refreshSessionQuietly();
        if (fullSyncService.startFullSync(false)) {
            log.info("[DesktopSync] scheduled full sync started");
        }
    }

    private void flush(String reason) {
        if (cloudSyncSession.load().isEmpty()) {
            return;
        }
        try {
            // Down first (cloud sales + supplies -> this till), then up (this
            // till's sales -> cloud): a sale made in the web portal lands on the
            // till within a couple of minutes, and vice versa. Talk to Us inbox
            // threads pull down (each message carries its full reply thread),
            // and queued replies push up (the cloud sends them).
            int pulled = syncPullService.pullCloudSales();
            int suppliesPulled = syncPullService.pullSupplies();
            int ordersPulled = syncPullService.pullWebOrders();
            DesktopMessagePullService.MessagePullResult messages = messagePullService.pullMessages();
            DesktopSyncPushService.SyncPushResult push = syncPushService.pushPending();
            DesktopMessagePushService.MessagePushResult replies = messagePushService.pushPendingReplies();
            if (pulled > 0 || suppliesPulled > 0 || ordersPulled > 0 || messages.messages() > 0
                    || messages.replies() > 0 || push.shiftsPushed() > 0 || push.salesPushed() > 0
                    || push.suppliesPushed() > 0 || push.orderConfirmationsPushed() > 0
                    || replies.repliesPushed() > 0) {
                log.info(
                    "[DesktopSync] {} flush: pulled {} cloud sale(s), {} supply session(s), "
                        + "{} web order(s), {} message(s) + {} reply(ies); pushed {} sale(s) in "
                        + "{} shift(s), {} supply session(s), {} order confirmation(s), "
                        + "relayed {} message reply(ies)",
                    reason, pulled, suppliesPulled, ordersPulled, messages.messages(), messages.replies(),
                    push.salesPushed(), push.shiftsPushed(), push.suppliesPushed(),
                    push.orderConfirmationsPushed(), replies.repliesPushed());
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
