package zelisline.ub.platform.realtime;

/**
 * Handles WebSocket channel lifecycle events and recomputes tenant presence
 * for the super-admin support inbox. Implemented in the support package; the
 * realtime handler only depends on this interface to avoid a package cycle.
 */
public interface SupportPresenceListener {

    /**
     * A session subscribed to (or left) {@code channel}, or a session that
     * could carry that channel closed. The implementer recomputes whether the
     * business still has anyone online and broadcasts the delta.
     *
     * @param businessId session business id ({@code "platform"} for super-admin sessions)
     * @param channel    channel that changed
     */
    void onChannelActivity(String businessId, String channel);
}
