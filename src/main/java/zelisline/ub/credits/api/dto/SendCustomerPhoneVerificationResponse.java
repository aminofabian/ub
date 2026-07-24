package zelisline.ub.credits.api.dto;

import java.time.Instant;

public record SendCustomerPhoneVerificationResponse(
        String phone,
        Instant expiresAt,
        String channel,
        String maskedHint
) {
}
