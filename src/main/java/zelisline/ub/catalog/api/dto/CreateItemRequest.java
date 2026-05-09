package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateItemRequest(
        /** When null or blank, a structured SKU is allocated from the category prefix sequence (e.g. PREFIX-10001). */
        @JsonDeserialize(using = EmptyToNullStringDeserializer.class)
        @Size(max = 191) String sku,
        @Size(max = 191) String barcode,
        @NotBlank @Size(max = 500) String name,
        @Size(max = 10_000) String description,
        @NotNull @Size(max = 36) String itemTypeId,
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
        BigDecimal buyingPrice,
        @Size(max = 255) String bundleName,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        Integer expiresAfterDays,
        Boolean hasExpiry,
        @Size(max = 2048) String imageKey,
        @Size(max = 255) String brand,
        @Size(max = 50) String size
) {
}
