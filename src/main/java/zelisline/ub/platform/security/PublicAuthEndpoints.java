package zelisline.ub.platform.security;

import java.util.Set;

/**
 * Auth routes that must stay reachable without a valid access token or API key,
 * even when the client sends a stale {@code Authorization} header from a prior session.
 */
public final class PublicAuthEndpoints {

    private static final Set<String> PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/email-lookup",
            "/api/v1/auth/login",
            "/api/v1/auth/login-pin",
            "/api/v1/auth/branches",
            "/api/v1/auth/refresh",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            "/api/v1/auth/clear-session-cookie",
            "/api/v1/supplier-portal/auth/login"
    );

    private PublicAuthEndpoints() {
    }

    public static boolean matches(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return false;
        }
        return PATHS.contains(requestUri);
    }
}
