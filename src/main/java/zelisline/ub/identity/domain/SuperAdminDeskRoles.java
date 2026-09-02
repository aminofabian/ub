package zelisline.ub.identity.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Desk roles for platform operators on the Customer Serving portal.
 *
 * <p>{@code owner} is the historical full console operator. {@code lead} is a CS
 * supervisor with the full console plus staff management. {@code agent} only
 * works tickets on Serving.
 */
public final class SuperAdminDeskRoles {

    public static final String OWNER = "owner";
    public static final String LEAD = "lead";
    public static final String AGENT = "agent";

    public static final String PERM_CONSOLE_FULL = "sa.console.full";
    public static final String PERM_SERVING_ACCESS = "sa.serving.access";
    public static final String PERM_STAFF_MANAGE = "sa.staff.manage";
    public static final String PERM_SERVING_ASSIGN = "sa.serving.assign";

    private static final Set<String> OWNER_PERMISSIONS = Set.of(
            PERM_CONSOLE_FULL,
            PERM_SERVING_ACCESS,
            PERM_STAFF_MANAGE,
            PERM_SERVING_ASSIGN
    );

    private static final Set<String> LEAD_PERMISSIONS = Set.of(
            PERM_CONSOLE_FULL,
            PERM_SERVING_ACCESS,
            PERM_STAFF_MANAGE,
            PERM_SERVING_ASSIGN
    );

    private static final Set<String> AGENT_PERMISSIONS = Set.of(
            PERM_SERVING_ACCESS
    );

    private SuperAdminDeskRoles() {
    }

    public static String normalize(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return OWNER;
        }
        String key = roleKey.trim().toLowerCase(Locale.ROOT);
        if (OWNER.equals(key) || LEAD.equals(key) || AGENT.equals(key)) {
            return key;
        }
        throw new IllegalArgumentException("Unknown desk role: " + roleKey);
    }

    public static String normalizeOrOwner(String roleKey) {
        try {
            return normalize(roleKey);
        } catch (IllegalArgumentException ex) {
            return OWNER;
        }
    }

    public static boolean isOwner(String roleKey) {
        return OWNER.equals(normalizeOrOwner(roleKey));
    }

    public static boolean isAgent(String roleKey) {
        return AGENT.equals(normalizeOrOwner(roleKey));
    }

    public static boolean canManageStaff(String roleKey) {
        String key = normalizeOrOwner(roleKey);
        return OWNER.equals(key) || LEAD.equals(key);
    }

    public static boolean canAssignAny(String roleKey) {
        return canManageStaff(roleKey);
    }

    public static boolean canSeeFullConsole(String roleKey) {
        String key = normalizeOrOwner(roleKey);
        return OWNER.equals(key) || LEAD.equals(key);
    }

    public static Set<String> permissionsFor(String roleKey) {
        String key = normalizeOrOwner(roleKey);
        if (AGENT.equals(key)) {
            return AGENT_PERMISSIONS;
        }
        if (LEAD.equals(key)) {
            return LEAD_PERMISSIONS;
        }
        return OWNER_PERMISSIONS;
    }

    public static List<GrantedAuthority> authoritiesFor(String roleKey) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        for (String perm : permissionsFor(roleKey)) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + perm));
        }
        return authorities;
    }
}
