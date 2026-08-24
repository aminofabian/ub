package zelisline.ub.platform.realtime;

/** Well-known scopes used in WebSocket tickets. */
public final class RealtimeScopes {

    private RealtimeScopes() {
    }

    /**
     * Business-id marker for super-admin (platform console) WebSocket sessions.
     * Such sessions carry the {@code SUPER_ADMIN} role id and can only subscribe
     * to channels minted on their ticket (e.g. {@code support}).
     */
    public static final String PLATFORM = "platform";
}
