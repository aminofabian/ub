package zelisline.ub.storefront;

/**
 * Canonical short order code (scope D11).
 *
 * <p>Derived deterministically from the order UUID — no column needed in V1 —
 * so the message, tracking link, dashboard, and ops alert all quote the same
 * code. Format: last 8 hex chars of the compact UUID, uppercased (the same
 * convention the dashboard already uses for row ids; replaces the ops alert's
 * first-8 variant).
 */
public final class WebOrderCodes {

    private WebOrderCodes() {
    }

    public static String code(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "";
        }
        String compact = orderId.replace("-", "");
        int start = Math.max(0, compact.length() - 8);
        return compact.substring(start).toUpperCase();
    }

    /** Case-insensitive match of a quoted code against an order id's suffix. */
    public static boolean matches(String code, String orderId) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String compact = orderId == null ? "" : orderId.replace("-", "");
        String suffix = compact.length() > 8
                ? compact.substring(compact.length() - 8)
                : compact;
        return code.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(suffix);
    }
}
