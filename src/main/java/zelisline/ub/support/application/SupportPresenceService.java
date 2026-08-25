package zelisline.ub.support.application;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import zelisline.ub.platform.realtime.RealtimeScopes;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.platform.realtime.SupportPresenceListener;

/**
 * Tracks which tenants are live on the support channel and broadcasts
 * {@code support.presence} deltas to the super-admin inbox so agents can see
 * who is online, who was last seen, and who is offline.
 *
 * <p>A tenant counts as <em>online</em> while at least one of its WebSocket
 * sessions is subscribed to the {@code support} channel. Last-seen is refreshed
 * by heartbeat pongs ({@link SessionRegistry#touchBusiness}).
 */
@Service
public class SupportPresenceService implements SupportPresenceListener {

    private static final Logger log = LoggerFactory.getLogger(SupportPresenceService.class);

    static final String CHANNEL = "support";
    static final String EVENT_TYPE = "support.presence";
    private static final String PRIORITY = "HIGH";

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;

    /** Last broadcast online-state per business — unchanged states are not re-broadcast. */
    private final Map<String, Boolean> lastBroadcastOnline = new ConcurrentHashMap<>();

    public SupportPresenceService(SessionRegistry sessionRegistry, RealtimeWebSocketHandler handler) {
        this.sessionRegistry = sessionRegistry;
        this.handler = handler;
    }

    @Override
    public void onChannelActivity(String businessId, String channel) {
        if (!CHANNEL.equals(channel) || RealtimeScopes.PLATFORM.equals(businessId)) {
            return;
        }
        broadcastIfChanged(businessId);
    }

    /** Whether the business currently has at least one live session on the support channel. */
    public boolean isOnline(String businessId) {
        for (String sessionId : sessionRegistry.findSessionsByBusinessChannel(businessId, CHANNEL)) {
            var session = sessionRegistry.getSession(sessionId);
            if (session != null && session.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Presence snapshot for a set of businesses (the super-admin inbox on load
     * and on periodic sync, so presence survives page loads and socket gaps).
     */
    public Map<String, Object> snapshot(Collection<String> businessIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String businessId : businessIds) {
            if (businessId == null || RealtimeScopes.PLATFORM.equals(businessId)) {
                continue;
            }
            Instant lastSeenAt = sessionRegistry.lastSeenForBusiness(businessId);
            result.put(businessId, Map.of(
                    "online", isOnline(businessId),
                    "lastSeenAt", lastSeenAt != null ? lastSeenAt.toString() : ""));
        }
        return result;
    }

    private void broadcastIfChanged(String businessId) {
        boolean online = isOnline(businessId);
        Boolean previous = lastBroadcastOnline.put(businessId, online);
        if (previous != null && previous == online) {
            return;
        }
        Instant lastSeenAt = sessionRegistry.lastSeenForBusiness(businessId);
        String payload = """
                {"businessId":"%s","online":%s,"lastSeenAt":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(businessId),
                online,
                lastSeenAt != null ? lastSeenAt.toString() : "");
        String eventId = UUID.randomUUID().toString();
        Set<String> adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
        for (String sessionId : adminSessions) {
            handler.sendFrame(sessionId, EVENT_TYPE, eventId, PRIORITY, null, payload);
        }
        log.debug("Support presence: business={} online={} adminSessions={}",
                businessId, online, adminSessions.size());
    }
}
