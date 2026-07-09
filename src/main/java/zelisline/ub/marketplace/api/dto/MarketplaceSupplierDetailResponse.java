package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketplaceSupplierDetailResponse(
        String id,
        String name,
        String description,
        String contactEmail,
        String contactPhone,
        String status,
        List<String> deliveryRegions,
        List<String> categoryTags,
        List<MarketplaceCatalogProductPreview> products
) {
    public record MarketplaceCatalogProductPreview(
            String id,
            String name,
            String barcode,
            String sku,
            String categoryName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            BigDecimal unitPrice,
            String currency,
            boolean available
    ) {
    }
}
