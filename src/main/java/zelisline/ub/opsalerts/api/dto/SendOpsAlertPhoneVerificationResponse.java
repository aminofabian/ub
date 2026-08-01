package zelisline.ub.opsalerts.api.dto;

import java.time.Instant;

public record SendOpsAlertPhoneVerificationResponse(
        String phone,
        Instant expiresAt,
        String channel,
        String phoneMasked
) {
}
