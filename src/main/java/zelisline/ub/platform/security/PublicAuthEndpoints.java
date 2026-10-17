package zelisline.ub.platform.security;

import java.util.List;
import java.util.Set;

/**
 * Auth routes that must stay reachable without a valid access token or API key,
 * even when the client sends a stale {@code Authorization} header from a prior session.
 *
 * <p>Magic-link prefixes (till trust, drawout approval) are included because the
 * browser proxy attaches whatever {@code ub.access} cookie is already present.
 * That session often belongs to a different shop than the host the link was
 * opened on, and the JWT tenant check would reject the link before its own
 * token is read.
 *
 * <p>Anonymously-readable public routes (marketplace directory, credit-claim
 * tabs, guest support chat, barcode lookup, page seals, onboarding mute) must
 * tolerate a leftover {@code ub.access} too: the proxy injects it as a Bearer,
 * and {@link JwtAuthenticationFilter} fails the whole request (401/403) on an
 * expired or different-shop token before the {@code permitAll} rule is reached.
 *
 * <p>Deliberately NOT listed: {@code /api/v1/public/businesses/**} (storefront)
 * and the shopper routes — those read the signed-in shopper via
 * {@code CurrentTenantUser}, so they must keep authenticating a valid token.
 */
public final class PublicAuthEndpoints {

    private static final List<String> PREFIXES = List.of(
            "/api/v1/public/tills/",
            "/api/v1/public/drawouts/",
            "/api/v1/public/marketplace/",
            "/api/v1/public/credits/",
            "/api/v1/public/support/",
            "/api/v1/public/barcode/",
            "/api/v1/public/page-seals/",
            "/api/v1/public/onboarding-sequence/"
    );

    private static final Set<String> PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/email-lookup",
            "/api/v1/auth/login",
            "/api/v1/auth/login-pin",
            "/api/v1/auth/unlock-pin",
            "/api/v1/auth/branches",
            "/api/v1/auth/refresh",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            "/api/v1/auth/clear-session-cookie",
            "/api/v1/auth/oauth/google/start",
            "/api/v1/auth/oauth/google/callback",
            "/api/v1/auth/oauth/google/exchange",
            "/api/v1/auth/oauth/google/shops",
            "/api/v1/auth/oauth/google/complete",
            "/api/v1/public/auth/oauth/google",
            "/api/v1/supplier-portal/auth/login",
            "/api/v1/supplier-portal/auth/claim/config",
            "/api/v1/supplier-portal/auth/claim/send-code",
            "/api/v1/supplier-portal/auth/claim/verify-code",
            "/api/v1/supplier-portal/auth/claim/verify-invite",
            "/api/v1/supplier-portal/auth/claim/complete",
            "/api/v1/public/shopper/auth/send-code",
            "/api/v1/public/shopper/auth/verify-code",
            "/api/v1/public/shopper/auth/session",
            "/api/v1/public/shopper/auth/identify/send-code",
            "/api/v1/public/shopper/auth/identify/verify-code",
            "/api/v1/public/shopper/auth/shops",
            "/api/v1/public/shopper/auth/destinations",
            "/api/v1/public/shops/search"
    );

    private PublicAuthEndpoints() {
    }

    public static boolean matches(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return false;
        }
        String path = requestUri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (PATHS.contains(path)) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
