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
    private final DesktopAuditPushService auditPushService;

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

        // Each stream is isolated: one failure is logged and the rest still
        // run, so a wedged sales ingest can no longer block supplies, web-order
        // confirmations or message replies on every cycle (WP-6).
        final int streams = 9;
        int failures = 0;
        String firstError = null;

        int pulled = 0;
        int suppliesPulled = 0;
        int ordersPulled = 0;
        int movementsApplied = 0;
        int customersPulled = 0;
        int auditsPushed = 0;
        DesktopMessagePullService.MessagePullResult messages =
            new DesktopMessagePullService.MessagePullResult(0, 0);
        DesktopSyncPushService.SyncPushResult push =
            new DesktopSyncPushService.SyncPushResult(0, 0, 0, 0, false);
        DesktopMessagePushService.MessagePushResult replies =
            new DesktopMessagePushService.MessagePushResult(0, false);

        try {
            pulled = syncPullService.pullCloudSales();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "cloud sales: " + e.getMessage() : firstError;
        }
        try {
            suppliesPulled = syncPullService.pullSupplies();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "supplies: " + e.getMessage() : firstError;
        }
        try {
            ordersPulled = syncPullService.pullWebOrders();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "web orders: " + e.getMessage() : firstError;
        }
        try {
            movementsApplied = syncPullService.pullInventoryMovements();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "stock movements: " + e.getMessage() : firstError;
        }
        try {
            customersPulled = syncPullService.pullCustomers();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "customers: " + e.getMessage() : firstError;
        }
        try {
            auditsPushed = auditPushService.pushPendingAudits();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "audit events: " + e.getMessage() : firstError;
        }
        try {
            messages = messagePullService.pullMessages();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "messages: " + e.getMessage() : firstError;
        }
        try {
            push = syncPushService.pushPending();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "push: " + e.getMessage() : firstError;
        }
        try {
            replies = messagePushService.pushPendingReplies();
        } catch (Exception e) {
            failures++;
            firstError = firstError == null ? "message replies: " + e.getMessage() : firstError;
        }

        boolean anyWork = pulled > 0 || suppliesPulled > 0 || ordersPulled > 0 || movementsApplied > 0
            || customersPulled > 0 || auditsPushed > 0
            || messages.messages() > 0 || messages.replies() > 0
            || push.shiftsPushed() > 0 || push.salesPushed() > 0 || push.suppliesPushed() > 0
            || push.orderConfirmationsPushed() > 0 || replies.repliesPushed() > 0;

        if (failures == 0) {
            if (anyWork) {
                log.info(
                    "[DesktopSync] {} flush: pulled {} cloud sale(s), {} supply session(s), "
                        + "{} web order(s), {} stock movement(s), {} customer(s), {} message(s) + {} reply(ies); "
                        + "pushed {} sale(s) in {} shift(s), {} supply session(s), "
                        + "{} order confirmation(s), relayed {} message reply(ies), forwarded {} audit event(s)",
                    reason, pulled, suppliesPulled, ordersPulled, movementsApplied, customersPulled,
                    messages.messages(), messages.replies(),
                    push.salesPushed(), push.shiftsPushed(), push.suppliesPushed(),
                    push.orderConfirmationsPushed(), replies.repliesPushed(), auditsPushed);
            } else if (push.configured()) {
                log.debug("[DesktopSync] {} flush: nothing pending", reason);
            }
        } else if (failures == streams) {
            // Everything failed — almost always no internet. Stay quiet.
            log.debug("[DesktopSync] {} flush: online shop unreachable (offline?): {}",
                reason, firstError);
        } else {
            log.warn(
                "[DesktopSync] {} flush: {}/{} stream(s) failed — sync is behind. First: {}",
                reason, failures, streams, firstError);
        }
    }
}
