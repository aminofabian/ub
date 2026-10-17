package zelisline.ub.identity.api.dto;

import java.util.List;

import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;

/**
 * Shops a verified Google account can still choose, after sign-in found more
 * than one staff membership. The pick token itself stays in an HttpOnly cookie.
 */
public record GoogleOAuthShopChoiceResponse(
        String email,
        List<PublicSignInDestinationResponse> destinations
) {}
