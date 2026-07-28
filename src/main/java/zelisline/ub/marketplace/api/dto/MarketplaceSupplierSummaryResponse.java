package zelisline.ub.marketplace.api.dto;

import java.time.Instant;
import java.util.List;

public record MarketplaceSupplierSummaryResponse(
        String id,
        String supplierNumber,
        String name,
        String description,
        String contactEmail,
        String status,
        String contactPhone,
        String username,
        long portalUserCount,
        long linkedShopCount,
        List<String> linkedShopNames,
        Instant createdAt,
        Instant updatedAt,
        Instant lastPortalLoginAt
) {
}
