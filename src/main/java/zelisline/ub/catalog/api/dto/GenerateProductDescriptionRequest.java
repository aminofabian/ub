package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateProductDescriptionRequest(
        @NotBlank String name,
        String categoryName,
        String brand,
        String size,
        String unitType,
        String variantName,
        String sku,
        String barcode
) {}
