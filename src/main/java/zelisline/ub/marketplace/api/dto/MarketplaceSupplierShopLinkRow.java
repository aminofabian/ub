package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record MarketplaceSupplierShopLinkRow(
        String connectionId,
        String businessId,
        String businessName,
        String businessSlug,
        String localSupplierId,
        String localSupplierName,
        String localSupplierStatus,
        String connectionStatus,
        Instant linkedAt
) {
}
