package zelisline.ub.desktop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zelisline.ub.desktop.application.DesktopFullSyncService;
import zelisline.ub.desktop.application.DesktopMediaSyncService;
import zelisline.ub.desktop.application.DesktopSetupService;
import zelisline.ub.desktop.application.DesktopSyncProgressService;
import zelisline.ub.desktop.application.DesktopSyncPushService;
import zelisline.ub.tenancy.repository.BusinessRepository;

import java.time.Instant;

/**
 * Sync triggers for the desktop install.
 *
 * <ul>
 *   <li>{@code POST /api/v1/desktop/sync} — push-only, fired automatically
 *       after shift close (fast).</li>
 *   <li>{@code POST /api/v1/desktop/sync/full} — pull master-data refresh
 *       <em>and</em> push pending shifts; used by Settings → Sync now (also
 *       started automatically on boot / periodically by
 *       {@link zelisline.ub.desktop.application.DesktopSyncScheduler}).</li>
 *   <li>{@code GET /api/v1/desktop/sync/status} — live progress of the
 *       background full sync.</li>
 * </ul>
 * All require a local staff session.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/sync")
@RequiredArgsConstructor
public class DesktopSyncTriggerController {

    private final DesktopSyncPushService syncPushService;
    private final DesktopMediaSyncService mediaSyncService;
    private final DesktopFullSyncService fullSyncService;
    private final DesktopSyncProgressService syncProgress;
    private final DesktopSetupService desktopSetupService;
    private final BusinessRepository businessRepository;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        boolean started = fullSyncService.startFullSync(true);
        return new SyncStartResult(started);
    }

    /** Current phase + counts of the background full sync. */
    @GetMapping("/status")
    public DesktopSyncProgressService.Snapshot syncStatus() {
        return syncProgress.snapshot();
    }

    /**
     * The online shop's subscription plan as last seen by the till (stamped
     * during each master-data pull) — so Settings → Sync can show
     * "Online shop plan: Growth · Active" and the two never look contradictory.
     */
    @GetMapping("/plan")
    public DesktopSyncPlan cloudPlan() {
        String localId = desktopSetupService.getDesktopBusinessId();
        if (localId.isEmpty()) {
            return new DesktopSyncPlan(null, null, null);
        }
        return businessRepository
            .findByIdAndDeletedAtIsNull(localId)
            .map(b -> readCloudPlan(b.getSettings()))
            .orElseGet(() -> new DesktopSyncPlan(null, null, null));
    }

    private static DesktopSyncPlan readCloudPlan(String settings) {
        if (settings == null || settings.isBlank()) {
            return new DesktopSyncPlan(null, null, null);
        }
        try {
            JsonNode desktop = JSON.readTree(settings).path("desktop");
            String expiresRaw = desktop.path("cloudPlanExpiresAt").asText(null);
            Instant expiresAt = null;
            if (expiresRaw != null && !expiresRaw.isBlank()) {
                try {
                    expiresAt = Instant.parse(expiresRaw.trim());
                } catch (Exception ignored) {
                    // Corrupt stamp — omit expiry rather than fail the whole plan read.
                }
            }
            return new DesktopSyncPlan(
                desktop.path("cloudPlanTier").asText(null),
                desktop.path("cloudPlanStatus").asText(null),
                expiresAt
            );
        } catch (Exception e) {
            return new DesktopSyncPlan(null, null, null);
        }
    }

    public record SyncStartResult(boolean started) {}

    /**
     * @param tier       cloud subscription tier (e.g. {@code growth}), null when unknown/not synced yet
     * @param status     cloud billing status ({@code ACTIVE}/{@code GRACE}/{@code SUSPENDED})
     * @param expiresAt  cloud {@code current_period_end}, null when unknown/free
     */
    public record DesktopSyncPlan(String tier, String status, Instant expiresAt) {}
}
