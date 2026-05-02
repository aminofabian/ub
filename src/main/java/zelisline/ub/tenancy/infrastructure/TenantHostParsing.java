package zelisline.ub.tenancy.infrastructure;

import java.util.Locale;

/**
 * Normalises host / {@code Host}-style values for tenant lookup (strip port, lower-case).
 */
public final class TenantHostParsing {

    private TenantHostParsing() {
    }

    public static String hostnameOnly(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("[")) {
            int end = t.indexOf(']');
            if (end > 0) {
                return t.substring(1, end);
            }
            return t;
        }
        int colon = t.indexOf(':');
        if (colon > 0 && t.indexOf(':', colon + 1) == -1) {
            return t.substring(0, colon);
        }
        return t;
    }
}
