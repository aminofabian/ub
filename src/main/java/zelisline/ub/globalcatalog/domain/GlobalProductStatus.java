package zelisline.ub.globalcatalog.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle values for {@link GlobalProduct#getStatus()}.
 *
 * <p>Tenant browse/adopt only see {@link #PUBLISHED}. There is no Java enum on the entity
 * (column is a string for Flyway/seed compatibility); use these constants for writes.
 */
public final class GlobalProductStatus {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String ARCHIVED = "archived";

    private static final Set<String> ALLOWED = Set.of(DRAFT, PUBLISHED, ARCHIVED);

    private GlobalProductStatus() {
    }

    public static boolean isAllowed(String status) {
        return status != null && ALLOWED.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return DRAFT;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalArgumentException("Invalid global product status: " + status);
        }
        return normalized;
    }

    public static boolean isVisibleToTenants(String status) {
        return PUBLISHED.equals(status);
    }
}
