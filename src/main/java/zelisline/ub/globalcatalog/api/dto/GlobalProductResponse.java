package zelisline.ub.globalcatalog.api.dto;

import java.math.BigDecimal;

public record GlobalProductResponse(
        String id,
        String catalogId,
        String globalCategoryId,
        String categoryName,
        String skuTemplate,
        String name,
        String brand,
        String size,
        String description,
        String barcode,
        String unitType,
        boolean weighed,
        boolean sellable,
        boolean stocked,
        BigDecimal recommendedBuyingPrice,
        BigDecimal recommendedSellingPrice,
        BigDecimal suggestedMarginPct,
        BigDecimal defaultReorderLevel,
        BigDecimal defaultReorderQty,
        BigDecimal defaultMinStockLevel,
        boolean hasExpiry,
        Integer expiresAfterDays,
        String imageUrl,
        String itemTypeKeyHint,
        int sortOrder,
        boolean alreadyImported,
        String adoptedItemId
) {
}
