package zelisline.ub.desktop.application;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Background full sync (master catalog + incremental sales/messages + push).
 *
 * <p>Used by Settings → Sync now and by {@link DesktopSyncScheduler} so the
 * till refreshes products/staff without the merchant pressing Sync every time.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopFullSyncService {

    private static final Logger log = LoggerFactory.getLogger(DesktopFullSyncService.class);

    private final CloudSyncSession cloudSyncSession;
    private final DesktopSyncPushService syncPushService;
    private final DesktopSyncPullService syncPullService;
    private final DesktopMessagePushService messagePushService;
    private final DesktopMessagePullService messagePullService;
    private final DesktopAuditPushService auditPushService;
    private final DesktopSyncProgressService syncProgress;

    /** Guards against overlapping start races before the progress phase flips. */
    private final AtomicBoolean startGate = new AtomicBoolean(false);

    /**
     * @return {@code true} when a new worker was started
     * @throws ResponseStatusException {@code 409} when a sync is already running
     *         and {@code failIfBusy} is true
     */
    public boolean startFullSync(boolean failIfBusy) {
        if (syncProgress.isRunning() || !startGate.compareAndSet(false, true)) {
            if (failIfBusy) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A sync is already in progress"
                );
            }
            return false;
        }
        if (cloudSyncSession.load().isEmpty()) {
            startGate.set(false);
            if (failIfBusy) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This till is not connected to an online shop yet"
                );
            }
            return false;
        }
        Thread worker = new Thread(
            () -> {
                try {
                    runFullSync();
                } finally {
                    startGate.set(false);
                }
            },
            "desktop-sync-full"
        );
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** Best-effort token refresh so the first pull does not burn a 401. */
    public void refreshSessionQuietly() {
        cloudSyncSession.load().ifPresent(session -> {
            if (session.origin() == null || session.origin().isBlank()) {
                return;
            }
            if (session.refreshToken() == null || session.refreshToken().isBlank()) {
                log.debug("[DesktopSync] no refresh token stored — reconnect still needed");
                return;
            }
            try {
                RestClient client = RestClient.builder().baseUrl(session.origin().trim()).build();
                cloudSyncSession.refresh(client, session).ifPresentOrElse(
                    refreshed -> log.info("[DesktopSync] refreshed online-shop session"),
                    () -> log.warn(
                        "[DesktopSync] could not refresh online-shop session — "
                            + "open Settings → Desktop → Reconnect if Sync keeps failing"
                    )
                );
            } catch (Exception e) {
                log.debug("[DesktopSync] session refresh skipped: {}", e.getMessage());
            }
        });
    }

    private void runFullSync() {
        try {
            // One-time: adopt the till's authoritative stock on the cloud before
            // replaying movements, so a mirror that drifted pre-fix is corrected
            // and the delta isn't mistaken for a cloud-origin change.
            if (!syncPullService.isStockReconciled()) {
                try {
                    int reconciled = syncPullService.reconcileStockToCloud();
                    syncPullService.markStockReconciled();
                    log.info(
                        "[DesktopSync] one-time stock reconcile: {} item(s) adopted by the cloud",
                        reconciled);
                } catch (Exception e) {
                    log.warn("[DesktopSync] stock reconcile deferred: {}", e.getMessage());
                }
            }
            // Each stream is isolated so one failure doesn't skip the rest
            // (WP-6): a wedged sales ingest no longer blocks supplies,
            // confirmations or messages.
            DesktopSyncPullService.PullResult pull = stream(
                "master-data", syncPullService::pullMasterData, null);
            int pulled = streamCount("cloud sales", syncPullService::pullCloudSales);
            int suppliesPulled = streamCount("supplies", syncPullService::pullSupplies);
            int ordersPulled = streamCount("web orders", syncPullService::pullWebOrders);
            int movementsApplied = streamCount("stock movements", syncPullService::pullInventoryMovements);
            int customersPulled = streamCount("customers", syncPullService::pullCustomers);
            DesktopMessagePullService.MessagePullResult messagePull = stream(
                "messages", messagePullService::pullMessages,
                new DesktopMessagePullService.MessagePullResult(0, 0));

            if (pull == null) {
                syncProgress.failed(
                    "Master data could not be refreshed — check the online-shop connection.");
                return;
            }

            syncProgress.uploadStarted();
            DesktopSyncPushService.SyncPushResult push = stream(
                "push", syncPushService::pushPending,
                new DesktopSyncPushService.SyncPushResult(0, 0, 0, 0, false));
            DesktopMessagePushService.MessagePushResult messagePush = stream(
                "message replies", messagePushService::pushPendingReplies,
                new DesktopMessagePushService.MessagePushResult(0, false));
            int auditsPushed = streamCount("audit events", auditPushService::pushPendingAudits);

            syncProgress.done(pull, push, messagePull, messagePush, suppliesPulled, ordersPulled);
            log.info(
                "[DesktopSync] full sync finished: {} item(s) refreshed, {} cloud sale(s) pulled, "
                    + "{} supply session(s) pulled, {} web order(s) pulled, {} stock movement(s) applied, "
                    + "{} customer(s) refreshed, {} sale(s) pushed, "
                    + "{} supply session(s) pushed, {} order confirmation(s) pushed, "
                    + "{} message(s) + {} reply(ies) pulled, {} reply(ies) relayed, {} audit event(s) forwarded",
                pull.items(), pulled, suppliesPulled, ordersPulled, movementsApplied, customersPulled,
                push.salesPushed(),
                push.suppliesPushed(), push.orderConfirmationsPushed(),
                messagePull.messages(), messagePull.replies(), messagePush.repliesPushed(),
                auditsPushed);
        } catch (Exception e) {
            log.warn("[DesktopSync] full sync failed: {}", e.getMessage());
            syncProgress.failed(e.getMessage());
        }
    }

    /** Run one pull stream, logging and swallowing its failure so the rest run. */
    private int streamCount(String label, java.util.function.IntSupplier call) {
        try {
            return call.getAsInt();
        } catch (Exception e) {
            log.warn("[DesktopSync] full sync: {} failed: {}", label, e.getMessage());
            return 0;
        }
    }

    /** Run one stream, logging and swallowing its failure so the rest run. */
    private <T> T stream(String label, java.util.function.Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("[DesktopSync] full sync: {} failed: {}", label, e.getMessage());
            return fallback;
        }
    }
}
