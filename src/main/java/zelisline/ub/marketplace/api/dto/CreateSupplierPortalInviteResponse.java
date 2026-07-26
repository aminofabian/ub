package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record CreateSupplierPortalInviteResponse(
        String inviteId,
        String marketplaceSupplierId,
        String claimCode,
        String phone,
        Instant expiresAt,
        boolean smsSent,
        String claimUrl
) {
}
