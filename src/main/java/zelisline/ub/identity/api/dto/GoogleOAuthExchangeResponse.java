package zelisline.ub.identity.api.dto;

/**
 * Result of {@code POST /api/v1/auth/oauth/google/exchange}. The refresh token is
 * delivered as an HttpOnly cookie (never the body); the same-host BFF mints
 * {@code ub.access} from {@link #accessToken()} and forwards {@code ub.refresh}.
 */
public record GoogleOAuthExchangeResponse(
        String accessToken,
        String nextPath,
        String slug
) {}
