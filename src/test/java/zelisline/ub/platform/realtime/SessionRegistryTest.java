package zelisline.ub.platform.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
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

    @Test
    void openSessionCountsByTenantBusinessSkipsClosedPlatformAndGuestSockets() {
        registerOpen("t1-till", "biz-1", "role", Set.of("pos"));
        registerOpen("t1-dash", "biz-1", "role", Set.of("pos", "support"));
        registerOpen("t2-till", "biz-2", "role", Set.of("pos"));
        register("t3-closed", "biz-3", null, Set.of("pos")); // mock isOpen() is false
        registerOpen("sa-1", RealtimeScopes.PLATFORM, "SUPER_ADMIN", Set.of("support"));
        registerOpen("guest-1", RealtimeScopes.GUEST, "GUEST", Set.of("support.guest:guest-1"));

        Map<String, Integer> counts = registry.openSessionCountsByTenantBusiness();

        assertEquals(Map.of("biz-1", 2, "biz-2", 1), counts);
        assertEquals(2, registry.countDistinctOnlineTenantBusinesses());
    }

    @Test
    void tenantLastSeenSnapshotKeepsTenantsOnlyAndSurvivesDisconnect() {
        registerOpen("t1-till", "biz-1", "role", Set.of("pos"));
        registerOpen("sa-1", RealtimeScopes.PLATFORM, "SUPER_ADMIN", Set.of("support"));
        registerOpen("guest-1", RealtimeScopes.GUEST, "GUEST", Set.of("support.guest:guest-1"));
        registry.touchBusiness("user-guest-1"); // heartbeat pongs touch guest sockets by guest id

        registry.unregister("t1-till");
        Map<String, Instant> seen = registry.tenantLastSeenSnapshot();

        assertEquals(Set.of("biz-1"), seen.keySet());
        assertTrue(registry.openSessionCountsByTenantBusiness().isEmpty());
    }

    private void registerOpen(String sessionId, String businessId, String roleId, Set<String> channels) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(sessionId);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession meta = new RealtimeSession("user-" + sessionId, businessId, roleId, null, channels);
        registry.register(sessionId, ws, meta, channels);
    }

    private void register(String sessionId, String businessId, String branchId, Set<String> channels) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(sessionId);
        RealtimeSession meta = new RealtimeSession("user-" + sessionId, businessId, "role", branchId, channels);
        registry.register(sessionId, ws, meta, channels);
    }
}
