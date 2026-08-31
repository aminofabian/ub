package zelisline.ub.desktop.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Live progress of the Settings → Sync now background job.
 *
 * <p>The full sync (pull master data + push closed shifts) can take minutes on
 * a shop with many products, so {@code POST /api/v1/desktop/sync/full} now
 * returns immediately and the UI polls {@code GET /api/v1/desktop/sync/status}
 * while the worker reports phases and item counts through this holder.
 *
 * <p>In-memory and per-install by design: a desktop install runs one JVM, so a
 * single volatile snapshot (immutable record) is sufficient — no locks needed.
 */
@Service
@Profile("desktop")
public class DesktopSyncProgressService {

    /** UI-facing phases of a full sync. */
    public enum Phase {
        /** No sync running (also the initial state). */
        IDLE,
        /** Pulling the master-data snapshot from the online shop. */
        DOWNLOADING,
        /** Upserting the snapshot into the local MariaDB. */
        APPLYING,
        /** Pushing closed shifts / sales up to the online shop. */
        UPLOADING,
        /** Finished successfully — {@link Snapshot#pull()} / {@link Snapshot#push()} hold the counts. */
        DONE,
        /** Failed — {@link Snapshot#error()} holds the reason. */
        ERROR
    }

    /** Immutable progress snapshot returned to the UI. */
    public record Snapshot(
        Phase phase,
        String detail,
        long startedAt,
        long finishedAt,
        int itemsDone,
        int itemsTotal,
        DesktopSyncPullService.PullResult pull,
        DesktopSyncPushService.SyncPushResult push,
        DesktopMessagePullService.MessagePullResult messagePull,
        DesktopMessagePushService.MessagePushResult messagePush,
        String error
    ) {
        public static Snapshot idle() {
            return new Snapshot(Phase.IDLE, "", 0L, 0L, 0, 0, null, null, null, null, null);
        }

        public boolean running() {
            return phase == Phase.DOWNLOADING
                || phase == Phase.APPLYING
                || phase == Phase.UPLOADING;
        }
    }

    private volatile Snapshot snapshot = Snapshot.idle();

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean isRunning() {
        return snapshot.running();
    }

    public void downloadStarted() {
        snapshot = new Snapshot(
            Phase.DOWNLOADING,
            "Downloading shop data from your online shop…",
            System.currentTimeMillis(),
            0L,
            0,
            0,
            null,
            null,
            null,
            null,
            null
        );
    }

    public void applyStarted(int itemsTotal) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(
            Phase.APPLYING,
            "Updating products…",
            s.startedAt(),
            s.finishedAt(),
            0,
            itemsTotal,
            s.pull(),
            s.push(),
            s.messagePull(),
            s.messagePush(),
            s.error()
        );
    }

    /** Called as items land during the apply phase ({@code done} = count so far). */
    public void applyProgress(int done) {
        Snapshot s = snapshot;
        if (s.phase() != Phase.APPLYING) {
            return;
        }
        snapshot = new Snapshot(
            s.phase(),
            s.detail(),
            s.startedAt(),
            s.finishedAt(),
            done,
            s.itemsTotal(),
            s.pull(),
            s.push(),
            s.messagePull(),
            s.messagePush(),
            s.error()
        );
    }

    public void uploadStarted() {
        Snapshot s = snapshot;
        snapshot = new Snapshot(
            Phase.UPLOADING,
            "Uploading sales to your online shop…",
            s.startedAt(),
            s.finishedAt(),
            s.itemsDone(),
            s.itemsTotal(),
            s.pull(),
            s.push(),
            s.messagePull(),
            s.messagePush(),
            s.error()
        );
    }

    public void done(
            DesktopSyncPullService.PullResult pull,
            DesktopSyncPushService.SyncPushResult push,
            DesktopMessagePullService.MessagePullResult messagePull,
            DesktopMessagePushService.MessagePushResult messagePush) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(
            Phase.DONE,
            "Sync finished",
            s.startedAt(),
            System.currentTimeMillis(),
            s.itemsDone(),
            s.itemsTotal(),
            pull,
            push,
            messagePull,
            messagePush,
            null
        );
    }

    public void failed(String message) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(
            Phase.ERROR,
            "Sync failed",
            s.startedAt(),
            System.currentTimeMillis(),
            s.itemsDone(),
            s.itemsTotal(),
            s.pull(),
            s.push(),
            s.messagePull(),
            s.messagePush(),
            message
        );
    }
}
