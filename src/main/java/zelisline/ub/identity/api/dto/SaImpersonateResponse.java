package zelisline.ub.identity.api.dto;

/**
 * Tenant session minted for a super-admin support session.
 * Tokens are returned in the JSON body so the SA console can hand them off
 * to the tenant subdomain (cookie-only Gap G path is same-origin).
 */
public record SaImpersonateResponse(
        String accessToken,
        String refreshToken,
        AuthUserResponse user,
        String businessId,
        String slug,
        String primaryDomain,
        String impersonatedBy,
        long expiresInSeconds
) {
}
