package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalClaimSendCodeResponse(
        String phone,
        String maskedPhone,
        Instant expiresAt,
        String channel,
        boolean alreadyRegistered,
        /** Present only when SMS is stubbed and expose-stub-otp is enabled (local/dev). */
        String devCode
) {
}
