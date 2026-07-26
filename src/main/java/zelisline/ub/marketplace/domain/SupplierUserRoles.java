package zelisline.ub.marketplace.domain;

import java.util.Locale;
import java.util.Set;

public final class SupplierUserRoles {

    /** Full owner access (claim / SA-created users default here). */
    public static final String ADMIN = "admin";

    /** Fulfilment / catalogue staff — no money or team admin. */
    public static final String STAFF = "staff";

    public static final String PERM_CATALOG_READ = "supplier.catalog.read";
    public static final String PERM_CATALOG_WRITE = "supplier.catalog.write";
    public static final String PERM_ORDERS_READ = "supplier.orders.read";
    public static final String PERM_ORDERS_RESPOND = "supplier.orders.respond";
    public static final String PERM_ORDERS_SHIP = "supplier.orders.ship";
    public static final String PERM_MONEY_READ = "supplier.money.read";
    public static final String PERM_MONEY_WRITE = "supplier.money.write";
    public static final String PERM_PROFILE_WRITE = "supplier.profile.write";
    public static final String PERM_TEAM_MANAGE = "supplier.team.manage";

    public static final Set<String> ADMIN_PERMISSIONS = Set.of(
            PERM_CATALOG_READ,
            PERM_CATALOG_WRITE,
            PERM_ORDERS_READ,
            PERM_ORDERS_RESPOND,
            PERM_ORDERS_SHIP,
            PERM_MONEY_READ,
            PERM_MONEY_WRITE,
            PERM_PROFILE_WRITE,
            PERM_TEAM_MANAGE
    );

    public static final Set<String> STAFF_PERMISSIONS = Set.of(
            PERM_CATALOG_READ,
            PERM_CATALOG_WRITE,
            PERM_ORDERS_READ,
            PERM_ORDERS_RESPOND,
            PERM_ORDERS_SHIP
    );

    private SupplierUserRoles() {
    }

    public static String normalize(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return ADMIN;
        }
        String key = roleKey.trim().toLowerCase(Locale.ROOT);
        if (STAFF.equals(key)) {
            return STAFF;
        }
        // owner is an accepted alias for admin
        if ("owner".equals(key) || ADMIN.equals(key)) {
            return ADMIN;
        }
        throw new IllegalArgumentException("Unknown supplier role: " + roleKey);
    }

    public static boolean isAdmin(String roleKey) {
        return ADMIN.equals(normalizeOrEmpty(roleKey));
    }

    public static Set<String> permissionsFor(String roleKey) {
        String key = normalizeOrEmpty(roleKey);
        if (STAFF.equals(key)) {
            return STAFF_PERMISSIONS;
        }
        if (ADMIN.equals(key)) {
            return ADMIN_PERMISSIONS;
        }
        return Set.of();
    }

    private static String normalizeOrEmpty(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return "";
        }
        String key = roleKey.trim().toLowerCase(Locale.ROOT);
        if ("owner".equals(key)) {
            return ADMIN;
        }
        return key;
    }
}
