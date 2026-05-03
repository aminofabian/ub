package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchItemRequest(
        @Size(max = 191) String barcode,
        @Size(max = 500) String name,
        @Size(max = 10_000) String description,
        @Size(max = 36) String categoryId,
        @Size(max = 36) String aisleId,
        @Size(max = 16) String unitType,
        Boolean isWeighed,
        Boolean isSellable,
        Boolean isStocked,
        @Size(max = 255) String packagingUnitName,
        BigDecimal packagingUnitQty,
        Integer bundleQty,
        BigDecimal bundlePrice,
        @Size(max = 255) String bundleName,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        Integer expiresAfterDays,
        Boolean hasExpiry,
        @Size(max = 2048) String imageKey,
        Boolean active,
        Boolean webPublished
) {
}
