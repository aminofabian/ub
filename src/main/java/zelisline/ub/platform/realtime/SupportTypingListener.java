package zelisline.ub.platform.realtime;

/**
 * Handles inbound "typing" frames from WebSocket clients and broadcasts them to
 * the other side of a support conversation. Implemented in the support package;
 * the realtime handler only depends on this interface to avoid a package cycle.
 */
public interface SupportTypingListener {

    /**
     * A connected WebSocket client started (or stopped) typing.
     *
     * @param userId         session user id
     * @param businessId     session business id ({@code "platform"} for super-admin sessions)
     * @param roleId         session role id ({@code "SUPER_ADMIN"} for platform sessions)
     * @param conversationId conversation being typed in (may be blank on the tenant side)
     * @param typing         true when typing started, false when it stopped
     */
    void onTyping(String userId, String businessId, String roleId, String conversationId, boolean typing);
}
