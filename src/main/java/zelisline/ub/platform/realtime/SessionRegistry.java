package zelisline.ub.platform.realtime;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Manages active WebSocket sessions on this instance.
 *
 * <p>In cloud profile, fan-out across instances is handled by Redis pub/sub —
 * this registry only tracks sessions local to this JVM.
 *
 * <p>In local/hybrid profiles, this is the sole session store.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RealtimeSession> sessionMeta = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    /** Last activity time per business, for presence "last seen" labels. */
    private final ConcurrentHashMap<String, Instant> lastSeenByBusiness = new ConcurrentHashMap<>();

    /**
     * Tenant businesses that have opened a socket since this instance started.
     * Keeps {@link #tenantLastSeenSnapshot()} free of platform and guest keys,
     * which share {@link #lastSeenByBusiness}.
     */
    private final Set<String> tenantBusinessesSeen = ConcurrentHashMap.newKeySet();

    /**
     * Register a newly opened WebSocket session.
     *
     * @param sessionId WebSocket session id
     * @param wsSession the Spring WebSocket session
     * @param meta      authenticated principal data from the ticket
     * @param channels  initial channel subscriptions
     */
    public void register(String sessionId, WebSocketSession wsSession, RealtimeSession meta, Set<String> channels) {
        sessions.put(sessionId, wsSession);
        sessionMeta.put(sessionId, meta);
        sessionSubscriptions.put(sessionId, ConcurrentHashMap.newKeySet());
        if (channels != null) {
            sessionSubscriptions.get(sessionId).addAll(channels);
        }
        lastSeenByBusiness.put(meta.businessId(), Instant.now());
        if (isTenantSession(meta)) {
            tenantBusinessesSeen.add(meta.businessId());
        }
        log.debug("WS session registered: id={} user={} business={}", sessionId, meta.userId(), meta.businessId());
    }

    /**
     * Remove a closed or disconnected session.
     */
    public void unregister(String sessionId) {
        WebSocketSession removed = sessions.remove(sessionId);
        sessionMeta.remove(sessionId);
        sessionSubscriptions.remove(sessionId);
        if (removed != null) {
            log.debug("WS session unregistered: id={}", sessionId);
        }
    }

    /**
     * Find a session by its Spring WebSocket session id.
     */
    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Get the authenticated metadata for a session.
     */
    public RealtimeSession getMeta(String sessionId) {
        return sessionMeta.get(sessionId);
    }

    /**
     * Get all channel subscriptions for a session.
     */
    public Set<String> getSubscriptions(String sessionId) {
        return sessionSubscriptions.getOrDefault(sessionId, Set.of());
    }

    /**
     * Update re-authenticated session metadata (e.g. after token refresh).
     */
    public void updateMeta(String sessionId, RealtimeSession meta) {
        sessionMeta.put(sessionId, meta);
    }

    /**
     * Find all session IDs for a given user within a business.
     * Used for multi-tab sync (notification.read echo).
     */
    public Set<String> findSessionsByUser(String businessId, String userId) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (businessId.equals(meta.businessId()) && userId.equals(meta.userId())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Find all session IDs subscribed to a given branch channel.
     * Used for branch-scoped fan-out (pos, stock, approvals).
     */
    public Set<String> findSessionsByBranchChannel(String businessId, String branchId, String channel) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (businessId.equals(meta.businessId())
                    && (branchId == null || branchId.equals(meta.branchId()))
                    && sessionSubscriptions.getOrDefault(entry.getKey(), Set.of()).contains(channel)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Branch sessions plus business-wide listeners (null {@code branchId} on the ticket).
     * Used for hub/owner surfaces that subscribe to {@code pos} without a fixed branch claim.
     */
    public Set<String> findSessionsByBranchOrBusinessWide(
            String businessId, String branchId, String channel) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (!businessId.equals(meta.businessId())) {
                continue;
            }
            if (!sessionSubscriptions.getOrDefault(entry.getKey(), Set.of()).contains(channel)) {
                continue;
            }
            String sessionBranch = meta.branchId();
            if (sessionBranch == null
                    || branchId == null
                    || branchId.equals(sessionBranch)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    /**
     * Open WebSocket count per tenant business (dashboard / till / support).
     * Excludes platform super-admin and anonymous guest scopes. Tenants with no
     * open socket are absent from the map.
     */
    public Map<String, Integer> openSessionCountsByTenantBusiness() {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (!isTenantSession(meta)) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen()) {
                counts.merge(meta.businessId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Distinct tenant businesses with at least one open WebSocket (dashboard /
     * till / support). Excludes platform super-admin and anonymous guest scopes.
     */
    public int countDistinctOnlineTenantBusinesses() {
        return openSessionCountsByTenantBusiness().size();
    }

    /**
     * Last recorded activity per tenant business on this instance, including
     * tenants that have since disconnected. Platform and guest keys are left out.
     */
    public Map<String, Instant> tenantLastSeenSnapshot() {
        Map<String, Instant> snapshot = new HashMap<>();
        for (String businessId : tenantBusinessesSeen) {
            Instant seenAt = lastSeenByBusiness.get(businessId);
            if (seenAt != null) {
                snapshot.put(businessId, seenAt);
            }
        }
        return snapshot;
    }

    private static boolean isTenantSession(RealtimeSession meta) {
        if (meta == null || "SUPER_ADMIN".equals(meta.roleId())) {
            return false;
        }
        String businessId = meta.businessId();
        return businessId != null
                && !businessId.isBlank()
                && !RealtimeScopes.PLATFORM.equals(businessId)
                && !RealtimeScopes.GUEST.equals(businessId);
    }

    /**
     * Distinct guest principals with at least one open guest-scoped socket.
     * Used for the SA support inbox "visitors online" counter.
     */
    public int countDistinctOnlineGuests() {
        java.util.HashSet<String> guests = new java.util.HashSet<>();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (meta == null || !RealtimeScopes.GUEST.equals(meta.businessId())) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen() && meta.userId() != null && !meta.userId().isBlank()) {
                guests.add(meta.userId());
            }
        }
        return guests.size();
    }

    public int activeSessionCountForBusiness(String businessId) {
        return activeOpenSessionCountForBusiness(businessId);
    }

    /** Count only open sessions — stale registry entries must not block new handshakes. */
    public int activeOpenSessionCountForBusiness(String businessId) {
        int count = 0;
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            if (!businessId.equals(entry.getValue().businessId())) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /** Record that a business had live activity right now (heartbeat pong). */
    public void touchBusiness(String businessId) {
        if (businessId != null && !businessId.isBlank()) {
            lastSeenByBusiness.put(businessId, Instant.now());
        }
    }

    /** Last recorded activity for a business, or null when never seen on this instance. */
    public Instant lastSeenForBusiness(String businessId) {
        return lastSeenByBusiness.get(businessId);
    }

    /**
     * Find all sessions of a business that subscribed to a channel.
     * Used for support-chat fan-out (every tenant user on the thread).
     */
    public Set<String> findSessionsByBusinessChannel(String businessId, String channel) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if (businessId.equals(meta.businessId())
                    && sessionSubscriptions.getOrDefault(entry.getKey(), Set.of()).contains(channel)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Find all platform (super-admin) sessions subscribed to a channel.
     * Super-admin tickets mint with a {@code SUPER_ADMIN} role and the platform scope.
     */
    public Set<String> findPlatformAdminSessions(String channel) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            RealtimeSession meta = entry.getValue();
            if ("SUPER_ADMIN".equals(meta.roleId())
                    && sessionSubscriptions.getOrDefault(entry.getKey(), Set.of()).contains(channel)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Find all session IDs for a given business.
     * Used for business-wide notification fan-out when no specific user is targeted.
     */
    public Set<String> findAllSessionsForBusiness(String businessId) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, RealtimeSession> entry : sessionMeta.entrySet()) {
            if (businessId.equals(entry.getValue().businessId())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}
