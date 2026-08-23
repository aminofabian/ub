package zelisline.ub.opsalerts.api.dto;

import java.time.Instant;

public record OpsAlertSettingsResponse(
        boolean enabled,
        String phone,
        String phoneMasked,
        boolean phoneVerified,
        Instant phoneVerifiedAt,
        boolean alertWebOrder,
        boolean alertShift,
        boolean alertSupply,
        boolean alertCreditPayment,
        boolean alertRestockDigest,
        boolean messagingReady
) {
}
