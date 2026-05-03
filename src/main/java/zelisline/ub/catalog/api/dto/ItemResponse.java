package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record ItemResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String description,
        String variantOfItemId,
        String variantName,
        String categoryId,
        String aisleId,
        String itemTypeId,
        String unitType,
        boolean isWeighed,
        boolean isSellable,
        boolean isStocked,
        String packagingUnitName,
        BigDecimal packagingUnitQty,
        Integer bundleQty,
        BigDecimal bundlePrice,
        String bundleName,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        Integer expiresAfterDays,
        boolean hasExpiry,
        String imageKey,
        boolean active,
        boolean webPublished,
        long version,
        List<ItemImageResponse> images,
        List<ItemSummaryResponse> variants
) {
}
