package zelisline.ub.platform.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    void findSessionsByBranchOrBusinessWideIncludesNullBranchListeners() {
        register("cashier-a", "biz-1", "branch-a", Set.of("pos"));
        register("owner-wide", "biz-1", null, Set.of("pos"));
        register("cashier-b", "biz-1", "branch-b", Set.of("pos"));
        register("other-biz", "biz-2", null, Set.of("pos"));

        Set<String> sessions = registry.findSessionsByBranchOrBusinessWide(
                "biz-1", "branch-a", "pos");

        assertEquals(2, sessions.size());
        assertTrue(sessions.contains("cashier-a"));
        assertTrue(sessions.contains("owner-wide"));
    }

    @Test
    void findSessionsByBranchChannelRequiresExactBranch() {
        register("cashier-a", "biz-1", "branch-a", Set.of("pos"));
        register("owner-wide", "biz-1", null, Set.of("pos"));

        Set<String> sessions = registry.findSessionsByBranchChannel(
                "biz-1", "branch-a", "pos");

        assertEquals(Set.of("cashier-a"), sessions);
    }

    private void register(String sessionId, String businessId, String branchId, Set<String> channels) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(sessionId);
        RealtimeSession meta = new RealtimeSession("user-" + sessionId, businessId, "role", branchId, channels);
        registry.register(sessionId, ws, meta, channels);
    }
}
