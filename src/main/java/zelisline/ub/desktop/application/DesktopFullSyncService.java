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
            DesktopSyncPullService.PullResult pull = syncPullService.pullMasterData();
            int pulled = syncPullService.pullCloudSales();
            int suppliesPulled = syncPullService.pullSupplies();
            int ordersPulled = syncPullService.pullWebOrders();
            DesktopMessagePullService.MessagePullResult messagePull = messagePullService.pullMessages();
            syncProgress.uploadStarted();
            DesktopSyncPushService.SyncPushResult push = syncPushService.pushPending();
            DesktopMessagePushService.MessagePushResult messagePush = messagePushService.pushPendingReplies();
            syncProgress.done(pull, push, messagePull, messagePush, suppliesPulled, ordersPulled);
            log.info(
                "[DesktopSync] full sync finished: {} item(s) refreshed, {} cloud sale(s) pulled, "
                    + "{} supply session(s) pulled, {} web order(s) pulled, {} sale(s) pushed, "
                    + "{} supply session(s) pushed, {} order confirmation(s) pushed, "
                    + "{} message(s) + {} reply(ies) pulled, {} reply(ies) relayed",
                pull.items(), pulled, suppliesPulled, ordersPulled, push.salesPushed(),
                push.suppliesPushed(), push.orderConfirmationsPushed(),
                messagePull.messages(), messagePull.replies(), messagePush.repliesPushed());
        } catch (Exception e) {
            log.warn("[DesktopSync] full sync failed: {}", e.getMessage());
            syncProgress.failed(e.getMessage());
        }
    }
}
