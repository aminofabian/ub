package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalTeamUserRow(
        String id,
        String name,
        String email,
        String phone,
        String roleKey,
        boolean active,
        Instant lastLoginAt,
        Instant createdAt,
        boolean currentUser
) {
}
