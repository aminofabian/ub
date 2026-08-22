package zelisline.ub.desktop.api;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.desktop.application.DesktopMediaSyncService;
import zelisline.ub.desktop.application.DesktopSyncProgressService;
import zelisline.ub.desktop.application.DesktopSyncPullService;
import zelisline.ub.desktop.application.DesktopSyncPushService;

/**
 * Sync triggers for the desktop install.
 *
 * <ul>
 *   <li>{@code POST /api/v1/desktop/sync} — push-only, fired automatically
 *       after shift close (fast).</li>
 *   <li>{@code POST /api/v1/desktop/sync/full} — pull master-data refresh
 *       <em>and</em> push pending shifts; used by Settings → Sync now.</li>
 *   <li>{@code GET /api/v1/desktop/sync/status} — live progress of the
 *       background full sync (the pull can take minutes on a big shop, so the
 *       sync runs off the HTTP thread and the UI polls this).</li>
 * </ul>
 * All require a local staff session.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/sync")
@RequiredArgsConstructor
public class DesktopSyncTriggerController {

    private static final Logger log = LoggerFactory.getLogger(
        DesktopSyncTriggerController.class
    );

    private final DesktopSyncPushService syncPushService;
    private final DesktopSyncPullService syncPullService;
    private final DesktopMediaSyncService mediaSyncService;
    private final DesktopSyncProgressService syncProgress;

    @PostMapping
    public DesktopSyncPushService.SyncPushResult syncNow() {
        return syncPushService.pushPending();
    }

    /** Live progress of the background product-photo download. */
    @GetMapping("/media-status")
    public DesktopMediaSyncService.MediaStatus mediaStatus() {
        return mediaSyncService.status();
    }

    /**
     * Start a background full sync (pull master data, then push pending
     * sales) and return immediately — progress is polled from
     * {@code GET /status}. Returns {@code 409} when one is already running.
     */
    @PostMapping("/full")
    public SyncStartResult syncFull() {
        if (syncProgress.isRunning()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A sync is already in progress"
            );
        }
        Thread worker = new Thread(this::runFullSync, "desktop-sync-full");
        worker.setDaemon(true);
        worker.start();
        return new SyncStartResult(true);
    }

    /** Current phase + counts of the background full sync. */
    @GetMapping("/status")
    public DesktopSyncProgressService.Snapshot syncStatus() {
        return syncProgress.snapshot();
    }

    public record SyncStartResult(boolean started) {}

    private void runFullSync() {
        try {
            DesktopSyncPullService.PullResult pull = syncPullService.pullMasterData();
            // Mirror sales made in the web portal / other tills into this till.
            int pulled = syncPullService.pullCloudSales();
            syncProgress.uploadStarted();
            DesktopSyncPushService.SyncPushResult push = syncPushService.pushPending();
            syncProgress.done(pull, push);
            log.info(
                "[DesktopSync] full sync finished: {} item(s) refreshed, {} cloud sale(s) pulled, {} sale(s) pushed",
                pull.items(),
                pulled,
                push.salesPushed()
            );
        } catch (Exception e) {
            log.warn("[DesktopSync] full sync failed: {}", e.getMessage());
            syncProgress.failed(e.getMessage());
        }
    }
}
