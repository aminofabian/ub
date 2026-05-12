package zelisline.ub.platform.realtime;

import java.util.Set;

/**
 * Authenticated principal data bound to an active WebSocket session.
 *
 * @param userId          authenticated user id
 * @param businessId      tenant id
 * @param roleId          user's role id (for permission checks)
 * @param branchId        optional branch context from the ticket
 * @param allowedChannels logical channel names the session may subscribe to
 */
public record RealtimeSession(
        String userId,
        String businessId,
        String roleId,
        String branchId,
        Set<String> allowedChannels
) {}
