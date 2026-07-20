package zelisline.ub.platform.security;

/**
 * Slug-in-URL and other tenantless public routes. These must stay reachable when
 * the browser still sends a stale {@code Authorization} header from a dashboard
 * session (common while browsing the storefront after signing in).
 */
public final class PublicApiEndpoints {

    private static final String PREFIX = "/api/v1/public/";

    private PublicApiEndpoints() {
    }

    public static boolean matches(String requestUri) {
        return requestUri != null && requestUri.startsWith(PREFIX);
    }
}
