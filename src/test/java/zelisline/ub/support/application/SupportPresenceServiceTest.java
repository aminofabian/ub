package zelisline.ub.support.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import zelisline.ub.platform.realtime.RealtimeSession;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;

class SupportPresenceServiceTest {

    private SessionRegistry sessionRegistry;
    private RealtimeWebSocketHandler handler;
    private SupportPresenceService presenceService;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistry();
        handler = mock(RealtimeWebSocketHandler.class);
        presenceService = new SupportPresenceService(sessionRegistry, handler);
        // One platform (super-admin) session subscribed to support — the broadcast target.
        sessionRegistry.register("admin-1", openSession(),
                new RealtimeSession("sa-1", "platform", "SUPER_ADMIN", null, Set.of("support")),
                Set.of("support"));
    }

    @Test
    void onlineWhenTenantSubscribesAndOfflineAfterClose() {
        WebSocketSession ws = openSession();
        sessionRegistry.register("sess-1", ws,
                new RealtimeSession("user-1", "biz-1", "", null, Set.of("support")),
                Set.of("support"));

        presenceService.onChannelActivity("biz-1", "support");
        assertTrue(presenceService.isOnline("biz-1"));
        verify(handler).sendFrame(eq("admin-1"), eq("support.presence"), anyString(),
                eq("HIGH"), any(), contains("\"online\":true"));

        sessionRegistry.unregister("sess-1");
        presenceService.onChannelActivity("biz-1", "support");
        assertFalse(presenceService.isOnline("biz-1"));
        verify(handler).sendFrame(anyString(), eq("support.presence"), anyString(),
                eq("HIGH"), any(), contains("\"online\":false"));
    }

    @Test
    void unchangedStateIsNotRebroadcast() {
        WebSocketSession ws = openSession();
        sessionRegistry.register("sess-1", ws,
                new RealtimeSession("user-1", "biz-1", "", null, Set.of("support")),
                Set.of("support"));

        presenceService.onChannelActivity("biz-1", "support");
        presenceService.onChannelActivity("biz-1", "support"); // still online → no-op

        verify(handler, times(1))
                .sendFrame(anyString(), eq("support.presence"), anyString(), eq("HIGH"), any(), anyString());
    }

    @Test
    void nonSupportChannelsAndPlatformScopeAreIgnored() {
        presenceService.onChannelActivity("biz-1", "notifications");
        presenceService.onChannelActivity("platform", "support");

        verify(handler, never()).sendFrame(anyString(), eq("support.presence"), anyString(),
                eq("HIGH"), any(), anyString());
    }

    @Test
    void snapshotReportsOnlineAndLastSeen() {
        WebSocketSession ws = openSession();
        sessionRegistry.register("sess-1", ws,
                new RealtimeSession("user-1", "biz-1", "", null, Set.of("support")),
                Set.of("support"));
        sessionRegistry.touchBusiness("biz-1");

        Map<String, Object> snapshot = presenceService.snapshot(List.of("biz-1", "biz-2"));

        assertEquals(2, snapshot.size());
        assertTrue((boolean) ((Map<?, ?>) snapshot.get("biz-1")).get("online"));
        assertFalse((boolean) ((Map<?, ?>) snapshot.get("biz-2")).get("online"));
        String lastSeen = (String) ((Map<?, ?>) snapshot.get("biz-1")).get("lastSeenAt");
        assertFalse(lastSeen.isEmpty());
        assertEquals(Instant.now().getEpochSecond(), Instant.parse(lastSeen).getEpochSecond(), 5);
    }

    private WebSocketSession openSession() {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }
}
