package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * Who among till cashiers may record drawouts when {@code pos.cashier_drawout} is on.
 * Owners, admins, and managers are not gated by this list.
 */
public record CashierDrawoutAccessResponse(
        /** {@code all} (default) or {@code selected}. */
        String scope,
        List<String> userIds
) {
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_SELECTED = "selected";

    public static CashierDrawoutAccessResponse allCashiers() {
        return new CashierDrawoutAccessResponse(SCOPE_ALL, List.of());
    }

    public boolean allowsUser(String userId) {
        if (!SCOPE_SELECTED.equalsIgnoreCase(scope == null ? "" : scope.trim())) {
            return true;
        }
        if (userId == null || userId.isBlank() || userIds == null || userIds.isEmpty()) {
            return false;
        }
        String needle = userId.trim();
        return userIds.stream().anyMatch(id -> id != null && needle.equalsIgnoreCase(id.trim()));
    }
}
