package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code cloud-sync.json} is written by overlapping threads (the scheduled
 * flush advancing a cursor, an {@code @Async} push thread rotating a token).
 * Each write must merge onto the freshest on-disk state so one writer cannot
 * drop another's change.
 */
class CloudSyncSessionTest {

    @TempDir
    Path tmp;

    private CloudSyncSession session() {
        CloudSyncSession session = new CloudSyncSession(new ObjectMapper());
        ReflectionTestUtils.setField(session, "appData", tmp.toString());
        return session;
    }

    @Test
    void cursorAdvanceKeepsTheFreshestTokenAndStaff() {
        CloudSyncSession session = session();
        session.persist(new CloudSyncSession.Session(
            "https://cloud.example", "biz", "fresh-token", "fresh-refresh",
            "owner", List.of("s1"), null, null, null, null, null));

        // A stale snapshot (old token, no cursors) advancing a cursor must not
        // overwrite the fresh token or the staff list on disk.
        CloudSyncSession.Session stale = new CloudSyncSession.Session(
            "https://cloud.example", "biz", "old-token", "old-refresh",
            "owner", List.of(), null, null, null, null, null);
        Instant cursor = Instant.parse("2026-08-20T10:00:00Z");
        session.persistLastSalesPullAt(stale, cursor);

        CloudSyncSession.Session loaded = session.load().orElseThrow();
        assertEquals("fresh-token", loaded.accessToken());
        assertEquals("fresh-refresh", loaded.refreshToken());
        assertEquals(List.of("s1"), loaded.staffIds());
        assertEquals(cursor, loaded.lastSalesPullAt());
    }

    @Test
    void advancingOneCursorLeavesTheOthersIntact() {
        CloudSyncSession session = session();
        Instant sales = Instant.parse("2026-08-20T09:00:00Z");
        Instant messages = Instant.parse("2026-08-20T09:30:00Z");
        session.persist(
            "https://cloud.example", "biz", "token", "refresh", "owner", List.of("s1"),
            sales, messages, null, null, null);

        Instant supplies = Instant.parse("2026-08-20T10:00:00Z");
        session.persistLastSuppliesPullAt(session.load().orElseThrow(), supplies);

        CloudSyncSession.Session loaded = session.load().orElseThrow();
        assertEquals(sales, loaded.lastSalesPullAt());
        assertEquals(messages, loaded.lastMessagesPullAt());
        assertEquals(supplies, loaded.lastSuppliesPullAt());
    }
}
