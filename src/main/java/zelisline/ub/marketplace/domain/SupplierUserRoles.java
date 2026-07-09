package zelisline.ub.marketplace.domain;

import java.util.Set;

public final class SupplierUserRoles {

    public static final String ADMIN = "admin";

    public static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "supplier.catalog.read",
            "supplier.catalog.write",
            "supplier.orders.read",
            "supplier.orders.respond",
            "supplier.orders.ship"
    );

    private SupplierUserRoles() {
    }

    public static Set<String> permissionsFor(String roleKey) {
        if (ADMIN.equals(roleKey)) {
            return ADMIN_PERMISSIONS;
        }
        return Set.of();
    }
}
