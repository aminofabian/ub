package zelisline.ub.marketplace.api.dto;

import java.util.List;

import zelisline.ub.suppliers.api.dto.PublicSupplierSupplyRow;
import zelisline.ub.suppliers.api.dto.SupplierPurchaseHistorySummary;

public record SupplierPortalHubShopDetailResponse(
        String businessId,
        String shopName,
        String localSupplierId,
        String localSupplierName,
        String currency,
        SupplierPurchaseHistorySummary summary,
        List<PublicSupplierSupplyRow> supplies
) {
}
