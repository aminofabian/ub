package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record ItemResponse(
        String id,
        String sku,
        String barcode,
        String pluCode,
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
        boolean packageVariant,
        BigDecimal currentStock,
        String packagingUnitName,
        BigDecimal packagingUnitQty,
        Integer bundleQty,
        BigDecimal bundlePrice,
        BigDecimal buyingPrice,
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
        List<ItemSummaryResponse> variants,
        String brand,
        String size,
        /**
         * On-hand at {@code branchId} when the detail endpoint was called with that query param;
         * otherwise null. Prefer this over {@link #currentStock()} for branch-scoped UIs
         * (in-store / branch stock from active batches).
         * For package variants this is available whole packages; see {@link #baseStockQty}.
         */
        BigDecimal stockQty,
        /** Parent/base on-hand in base units when {@code packageVariant} and branch stock was requested. */
        BigDecimal baseStockQty
) {
}
