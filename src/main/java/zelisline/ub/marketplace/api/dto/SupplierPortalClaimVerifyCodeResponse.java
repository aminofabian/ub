package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalClaimVerifyCodeResponse(
        String setupToken,
        Instant expiresAt,
        String suggestedName
) {
}
