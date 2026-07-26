package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

public record SupplierPortalShopProductRow(
        String itemId,
        String itemName,
        String sku,
        String barcode,
        String thumbnailUrl,
        BigDecimal currentStock,
        BigDecimal defaultCostPrice,
        BigDecimal lastCostPrice,
        BigDecimal packSize,
        String packUnit,
        String variantName,
        String parentItemName
) {
}
