package zelisline.ub.marketplace.api.dto;

import java.time.Instant;
import java.util.Map;

public record MarketplaceProductEditRequestRow(
        String id,
        String marketplaceSupplierId,
        String supplierName,
        String productId,
        String productName,
        String status,
        Map<String, Object> proposed,
        Map<String, Object> liveSnapshot,
        Instant createdAt,
        Instant reviewedAt,
        String reviewNote
) {
}
