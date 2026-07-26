package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record MarketplaceSupplierUserRow(
        String id,
        String marketplaceSupplierId,
        String email,
        String phone,
        String name,
        String roleKey,
        boolean active,
        Instant lastLoginAt,
        Instant lockedUntil,
        Instant createdAt
) {
}
