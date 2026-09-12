package zelisline.ub.suppliers.api.dto;

import java.time.Instant;

public record VerifySupplierPayoutPhoneResponse(
        String phone,
        String maskedPhone,
        Instant payoutPhoneVerifiedAt
) {
}
