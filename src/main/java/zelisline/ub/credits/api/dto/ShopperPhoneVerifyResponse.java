package zelisline.ub.credits.api.dto;

import java.time.Instant;

public record ShopperPhoneVerifyResponse(
        String phoneVerificationToken,
        Instant expiresAt,
        boolean hasPin,
        String customerName
) {
}
