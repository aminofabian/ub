package zelisline.ub.suppliers.api.dto;

import java.time.Instant;

public record SendSupplierPayoutPhoneVerificationResponse(
        String phone,
        Instant expiresAt,
        String channel,
        String maskedPhone
) {
}
