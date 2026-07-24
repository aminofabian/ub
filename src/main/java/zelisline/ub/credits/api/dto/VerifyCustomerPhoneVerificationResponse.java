package zelisline.ub.credits.api.dto;

import java.time.Instant;

public record VerifyCustomerPhoneVerificationResponse(
        String phoneVerificationToken,
        Instant expiresAt
) {
}
