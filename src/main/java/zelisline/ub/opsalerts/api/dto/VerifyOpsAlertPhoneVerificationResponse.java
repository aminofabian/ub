package zelisline.ub.opsalerts.api.dto;

import java.time.Instant;

public record VerifyOpsAlertPhoneVerificationResponse(
        String phone,
        String phoneMasked,
        Instant phoneVerifiedAt
) {
}
