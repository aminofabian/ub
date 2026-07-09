package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSupplierPortalProductRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 191) String barcode,
        @Size(max = 191) String sku,
        @Size(max = 255) String categoryName,
        @Size(max = 5000) String description,
        @DecimalMin("0.0001") BigDecimal packSize,
        @Size(max = 32) String packUnit,
        @DecimalMin("0.0001") BigDecimal minOrderQty,
        @NotNull @DecimalMin("0") BigDecimal unitPrice,
        @Size(min = 3, max = 3) String currency,
        Boolean available
) {
}
