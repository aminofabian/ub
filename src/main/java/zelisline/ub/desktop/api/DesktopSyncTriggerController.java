package zelisline.ub.desktop.api;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
 * </ul>
 * Both require a local staff session.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/sync")
@RequiredArgsConstructor
public class DesktopSyncTriggerController {

    private final DesktopSyncPushService syncPushService;
    private final DesktopSyncPullService syncPullService;

    @PostMapping
    public DesktopSyncPushService.SyncPushResult syncNow() {
        return syncPushService.pushPending();
    }

    /** Pull master data down, then push pending sales up. */
    @PostMapping("/full")
    public FullSyncResult syncFull() {
        DesktopSyncPullService.PullResult pull = syncPullService.pullMasterData();
        DesktopSyncPushService.SyncPushResult push = syncPushService.pushPending();
        return new FullSyncResult(pull, push);
    }

    public record FullSyncResult(
        DesktopSyncPullService.PullResult pull,
        DesktopSyncPushService.SyncPushResult push
    ) {}
}
