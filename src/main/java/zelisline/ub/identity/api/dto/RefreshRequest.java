package zelisline.ub.identity.api.dto;

/** Refresh token from httpOnly cookie and/or JSON body (legacy / handoff migration). */
public record RefreshRequest(
        String refreshToken
) {
}
