package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVariantRequest(
        @NotBlank @Size(max = 191) String sku,
        @NotBlank @Size(max = 255) String variantName,
        @Size(max = 191) String barcode,
        @Size(max = 500) String name,
        @Size(max = 10_000) String description,
        @Size(max = 36) String categoryId,
        @Size(max = 36) String aisleId,
        @Size(max = 16) String unitType,
        Boolean isWeighed,
        Boolean isSellable,
        Boolean isStocked,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        @Size(max = 500) String imageKey
) {
}
