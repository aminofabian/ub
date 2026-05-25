package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchItemRequest(
        String expectedUpdatedAt,
        @Size(max = 191) String sku,
        @Size(max = 191) String barcode,
        @Size(max = 500) String name,
        @Size(max = 10_000) String description,
        @Size(max = 36) String categoryId,
        @Size(max = 36) String aisleId,
        /**
         * Department (item type) the SKU should belong to. When provided the value
         * must be a non-blank id of an existing item type for the business.
         */
        @Size(max = 36) String itemTypeId,
        @Size(max = 16) String unitType,
        Boolean isWeighed,
        Boolean isSellable,
        Boolean isStocked,
        Boolean packageVariant,
        @Size(max = 255) String packagingUnitName,
        BigDecimal packagingUnitQty,
        Integer bundleQty,
        BigDecimal bundlePrice,
        BigDecimal buyingPrice,
        @Size(max = 255) String bundleName,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        Integer expiresAfterDays,
        Boolean hasExpiry,
        @Size(max = 2048) String imageKey,
        Boolean active,
        Boolean webPublished,
        @Size(max = 255) String brand,
        @Size(max = 50) String size,
        /** Option / variant label; only applied when the item is a variant (has {@code variantOfItemId}). */
        @Size(max = 255) String variantName
) {
}
