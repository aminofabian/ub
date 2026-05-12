package zelisline.ub.platform.realtime;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Validates that an authenticated session may subscribe to a given logical channel.
 *
 * <p>Channel permission mapping:
 * <ul>
 *   <li>{@code notifications} — any authenticated user (already checked at ticket mint)</li>
 *   <li>{@code pos} — requires {@code sales.read} (cashiers, managers, owners)</li>
 *   <li>{@code stock} — requires {@code inventory.read} (stock clerks, managers)</li>
 *   <li>{@code approvals} — requires {@code approvals.approve} or {@code approvals.request}</li>
 *   <li>{@code transfers} — requires {@code inventory.read} (Phase 2)</li>
 * </ul>
 *
 * <p>Permission checks are role-key-based using the existing permission model.
 * The actual permission evaluation is delegated to the caller which has access
 * to the user's role and the {@code PermissionEvaluator}.
 */
@Component
public class ChannelAuthorizationManager {

    private static final Set<String> BRANCH_SCOPED_CHANNELS = Set.of("pos", "stock", "approvals", "transfers");

    /**
     * Check whether a session may subscribe to a channel.
     *
     * @param channel  logical channel name
     * @param meta     the authenticated session metadata (user, business, role, branch)
     * @return true if the subscription is authorized
     */
    public boolean maySubscribe(String channel, RealtimeSession meta) {
        // notifications channel is always allowed (already gated at ticket mint)
        if ("notifications".equals(channel)) {
            return true;
        }

        // Branch-scoped channels require a branch_id on the session
        if (BRANCH_SCOPED_CHANNELS.contains(channel) && meta.branchId() == null) {
            return false;
        }

        // Specific channel permissions are enforced at the ticket level
        // (allowedChannels set from ticket mint). The caller validates
        // against allowedChannels in RealtimeWebSocketHandler.
        return meta.allowedChannels().contains(channel);
    }

    /**
     * The required permission key for a given channel.
     * Used by ticket minting to determine which channels to include.
     */
    public static String requiredPermission(String channel) {
        return switch (channel) {
            case "pos" -> "sales.read";
            case "stock" -> "inventory.read";
            case "approvals" -> "approvals.approve";
            case "transfers" -> "inventory.read";
            default -> null;
        };
    }
}
