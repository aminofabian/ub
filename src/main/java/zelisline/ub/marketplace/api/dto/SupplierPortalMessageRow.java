package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalMessageRow(
        String id,
        String businessId,
        String shopName,
        String localSupplierId,
        String direction,
        String authorName,
        String body,
        Instant createdAt,
        Instant readAt
) {
}
