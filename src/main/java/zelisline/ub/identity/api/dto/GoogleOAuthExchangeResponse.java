package zelisline.ub.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of {@code POST /api/v1/auth/oauth/google/exchange} or
 * {@code POST /api/v1/auth/oauth/google/complete}. The refresh token is
 * delivered as an HttpOnly cookie (never the body); the same-host BFF mints
 * {@code ub.access} from {@link #accessToken()} and forwards {@code ub.refresh}.
 *
 * <p>When {@link #status()} is {@code choose_shop}, no session is issued yet.
 * The browser is sent to the office sign-in shop picker; {@link #email()} is
 * the verified Google address.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoogleOAuthExchangeResponse(
        String accessToken,
        String nextPath,
        String slug,
        String returnHost,
        String status,
        String email
) {
    public static final String STATUS_CHOOSE_SHOP = "choose_shop";

    public GoogleOAuthExchangeResponse(
            String accessToken, String nextPath, String slug, String returnHost) {
        this(accessToken, nextPath, slug, returnHost, null, null);
    }
}
